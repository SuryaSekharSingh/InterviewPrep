from __future__ import annotations

import hashlib
import json
import os
import random
import tempfile
import threading
import time
import uuid
import wave
import zipfile
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import BackgroundTasks, Depends, FastAPI, File, Header, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import ai, config, security
from .db import all_rows, one, run_migrations, transaction
from .errors import ApiError, install_error_handlers

User = Annotated[str, Depends(security.current_user)]


def admin_user(user_id: User) -> str:
    if user_id not in config.ADMIN_UIDS:
        raise ApiError(403, "FORBIDDEN", "Administrator access is required.")
    return user_id


Admin = Annotated[str, Depends(admin_user)]


def dumps(value) -> str:
    return json.dumps(value, separators=(",", ":"), default=str)


def loads(value, fallback=None):
    if value is None:
        return fallback
    return json.loads(value) if isinstance(value, str) else value


def now() -> datetime:
    return datetime.now(timezone.utc)


def activity_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "userId": row["user_id"],
        "module": row["module"],
        "state": row["state"],
        "context": loads(row["context"], {}),
        "report": loads(row.get("report")),
        "score": row.get("score"),
        "eligible": row["eligible"],
        "version": row["version"],
        "createdAt": row["created_at"],
        "completedAt": row.get("completed_at"),
    }


def owned_activity(connection, user_id: str, activity_id: str, lock=False) -> dict:
    suffix = " FOR UPDATE" if lock else ""
    return one(
        connection,
        "SELECT * FROM activity WHERE id=%s AND user_id=%s" + suffix,
        (activity_id, user_id),
    )


def create_activity(
    connection, user_id: str, module: str, key: str, context: dict
) -> dict:
    if not key or len(key) > 100:
        raise ApiError(422, "INVALID_INPUT", "An idempotency key is required.")
    request_hash = hashlib.sha256(dumps(context).encode()).hexdigest()
    existing = one(
        connection,
        "SELECT * FROM activity WHERE user_id=%s AND submission_key=%s",
        (user_id, key),
        required=False,
    )
    if existing:
        if existing["request_hash"] != request_hash or existing["module"] != module:
            raise ApiError(
                409,
                "IDEMPOTENCY_CONFLICT",
                "This submission key was used for different settings.",
            )
        return existing
    activity_id = str(uuid.uuid4())
    connection.execute(
        "INSERT INTO activity(id,user_id,module,state,context,submission_key,request_hash) "
        "VALUES(%s,%s,%s,'ACTIVE',%s,%s,%s)",
        (activity_id, user_id, module, dumps(context), key, request_hash),
    )
    return one(connection, "SELECT * FROM activity WHERE id=%s", (activity_id,))


def complete_activity(
    connection, row: dict, report: dict, score: float | None, competencies: dict
):
    completed = now()
    connection.execute(
        "UPDATE activity SET state='COMPLETED',report=%s,score=%s,eligible=%s,"
        "version=version+1,completed_at=%s WHERE id=%s",
        (dumps(report), score, score is not None, completed, row["id"]),
    )
    if score is not None:
        context = loads(row["context"], {})
        retry_group = context.get("retryGroup") or row["id"]
        connection.execute(
            "INSERT INTO progress_activity(activity_id,user_id,module,score,retry_group,context,completed_at) "
            "VALUES(%s,%s,%s,%s,%s,%s,%s) ON CONFLICT(activity_id) DO UPDATE SET score=EXCLUDED.score,context=EXCLUDED.context",
            (
                row["id"],
                row["user_id"],
                row["module"],
                score,
                retry_group,
                dumps(context),
                completed,
            ),
        )
        for competency, value in competencies.items():
            connection.execute(
                "INSERT INTO competency_evidence(activity_id,competency,score) VALUES(%s,%s,%s) "
                "ON CONFLICT(activity_id,competency) DO UPDATE SET score=EXCLUDED.score",
                (row["id"], competency, value),
            )
        summary = progress_summary(connection, row["user_id"])
        connection.execute(
            "INSERT INTO progress_snapshot(id,user_id,source_activity,scoring_version,summary) "
            "VALUES(%s,%s,%s,'v1',%s) ON CONFLICT(source_activity,scoring_version) "
            "DO UPDATE SET summary=EXCLUDED.summary,created_at=CURRENT_TIMESTAMP",
            (str(uuid.uuid4()), row["user_id"], row["id"], dumps(summary)),
        )


def job_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "kind": row["kind"],
        "state": row["state"],
        "attempts": row["attempts"],
        "errorCode": row.get("error_code"),
    }


def enqueue_job(
    connection, user_id: str, kind: str, activity_id: str, payload: dict
) -> dict:
    dedupe = f"{kind.lower()}:{activity_id}:{payload.get('sequence', '')}"
    existing = one(
        connection, "SELECT * FROM job WHERE dedupe_key=%s", (dedupe,), required=False
    )
    if existing:
        return existing
    job_id = str(uuid.uuid4())
    connection.execute(
        "INSERT INTO job(id,user_id,kind,payload,dedupe_key,activity_id) VALUES(%s,%s,%s,%s,%s,%s)",
        (job_id, user_id, kind, dumps(payload), dedupe, activity_id),
    )
    _worker_wake.set()
    return one(connection, "SELECT * FROM job WHERE id=%s", (job_id,))


def mark_job(job_id: str, state: str, error: str | None = None):
    with transaction() as connection:
        connection.execute(
            "UPDATE job SET state=%s,error_code=%s,attempts=attempts+1,lease_until=NULL WHERE id=%s",
            (state, error, job_id),
        )


_worker_stop = threading.Event()
_worker_wake = threading.Event()
_worker_kinds = ("GRADE_TEST", "INTERVIEW_ANSWER", "TRANSCRIBE", "ENGLISH_REPORT")


def dispatch_job(job: dict):
    payload = loads(job["payload"], {})
    if job["kind"] == "GRADE_TEST":
        process_test(job["user_id"], payload["activityId"], job["id"])
    elif job["kind"] == "INTERVIEW_ANSWER":
        process_interview_answer(
            job["user_id"], payload["activityId"], payload["sequence"], job["id"]
        )
    elif job["kind"] == "TRANSCRIBE":
        process_transcription(job["user_id"], payload["mediaId"], job["id"])
    elif job["kind"] == "ENGLISH_REPORT":
        process_english(job["user_id"], payload["activityId"], job["id"])


def job_worker():
    while not _worker_stop.is_set():
        job = None
        try:
            with transaction() as connection:
                job = one(
                    connection,
                    "SELECT * FROM job WHERE state='QUEUED' AND available_at<=CURRENT_TIMESTAMP "
                    "AND kind=ANY(%s) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1",
                    (list(_worker_kinds),),
                    required=False,
                )
                if job:
                    connection.execute(
                        "UPDATE job SET state='RUNNING',lease_until=%s WHERE id=%s",
                        (now() + timedelta(minutes=15), job["id"]),
                    )
            if job:
                dispatch_job(job)
                continue
        except Exception:
            if job:
                mark_job(job["id"], "FAILED", "WORKER_ERROR")
        _worker_wake.wait(0.5)
        _worker_wake.clear()


@asynccontextmanager
async def lifespan(_: FastAPI):
    run_migrations()
    config.MEDIA_ROOT.mkdir(parents=True, exist_ok=True)
    with transaction() as connection:
        connection.execute(
            "UPDATE job SET state='QUEUED',lease_until=NULL WHERE state='RUNNING' "
            "AND kind=ANY(%s)",
            (list(_worker_kinds),),
        )
    _worker_stop.clear()
    worker = threading.Thread(target=job_worker, name="interviewedge-jobs", daemon=True)
    worker.start()
    _worker_wake.set()
    try:
        yield
    finally:
        _worker_stop.set()
        _worker_wake.set()
        worker.join(timeout=5)


app = FastAPI(title="InterviewEdge local API", version="0.2.0", lifespan=lifespan)
install_error_handlers(app)
_rate_lock = threading.Lock()
_rate_windows: dict[str, deque[float]] = defaultdict(deque)


@app.middleware("http")
async def rate_limit(request: Request, call_next):
    moment = time.monotonic()
    auth_route = (
        request.url.path.startswith("/api/v1/auth/")
        and request.url.path != "/api/v1/auth/logout"
    )
    authorization = request.headers.get("authorization", "")
    identity = (
        hashlib.sha256(authorization.encode()).hexdigest()
        if authorization
        else (request.client.host if request.client else "local")
    )
    key = ("auth:" if auth_route else "api:") + identity
    maximum = 10 if auth_route else 180
    with _rate_lock:
        window = _rate_windows[key]
        while window and window[0] <= moment - 60:
            window.popleft()
        if len(window) >= maximum:
            return JSONResponse(
                status_code=429,
                content={
                    "code": "RATE_LIMITED",
                    "message": "Please wait before retrying.",
                },
            )
        window.append(moment)
        if len(_rate_windows) > 2000:
            for stale in [
                name
                for name, values in _rate_windows.items()
                if not values or values[-1] <= moment - 60
            ]:
                _rate_windows.pop(stale, None)
    return await call_next(request)


@app.get("/health")
def health():
    with transaction() as connection:
        connection.execute("SELECT 1")
    return {"application": "InterviewEdge", "backend": "FastAPI", "status": "UP"}


@app.post("/api/v1/auth/register")
def auth_register(body: dict):
    return security.register(body.get("username"), body.get("password"))


@app.post("/api/v1/auth/login")
def auth_login(body: dict):
    return security.login(body.get("username"), body.get("password"))


@app.post("/api/v1/auth/recover")
def auth_recover(body: dict):
    return security.recover(
        body.get("username"), body.get("recoveryCode"), body.get("newPassword")
    )


@app.post("/api/v1/auth/password")
def auth_password(body: dict, user_id: User):
    return security.change_password(
        user_id, body.get("currentPassword"), body.get("newPassword")
    )


@app.post("/api/v1/auth/reauthenticate")
def auth_reauthenticate(body: dict, user_id: User):
    with transaction() as connection:
        account = one(
            connection,
            "SELECT username FROM local_account WHERE user_id=%s",
            (user_id,),
        )
    return security.login(account["username"], body.get("password"))


@app.post("/api/v1/auth/logout")
def auth_logout(authorization: Annotated[str, Header()], _: User):
    with transaction() as connection:
        connection.execute(
            "DELETE FROM login_session WHERE token_hash=%s",
            (security.token_hash(authorization[7:]),),
        )
    return {"signedOut": True}


def profile_row(connection, user_id: str) -> dict:
    row = one(connection, "SELECT * FROM profile WHERE user_id=%s", (user_id,))
    return {
        "displayName": row["display_name"],
        "roleId": row["role_id"],
        "weeklyGoal": row["weekly_goal"],
        "education": row["education"],
        "skills": loads(row["skills"], []),
        "timezone": row["timezone"],
        "retentionDays": row["retention_days"],
        "consentVersion": row["consent_version"],
    }


@app.get("/api/v1/me")
def get_profile(user_id: User):
    with transaction() as connection:
        return profile_row(connection, user_id)


@app.patch("/api/v1/me")
def update_profile(body: dict, user_id: User):
    with transaction() as connection:
        current = profile_row(connection, user_id)
        value = {**current, **body}
        if not 1 <= int(value["weeklyGoal"]) <= 30 or int(
            value["retentionDays"]
        ) not in (7, 30):
            raise ApiError(
                422, "INVALID_INPUT", "Check the weekly goal and retention period."
            )
        if value.get("consentVersion") not in (None, "", "privacy-v1"):
            raise ApiError(422, "INVALID_INPUT", "Unknown consent version.")
        role = one(
            connection,
            "SELECT skills FROM role_catalog WHERE id=%s",
            (value["roleId"],),
            required=False,
        )
        if not role or not set(value.get("skills", [])).issubset(
            set(loads(role["skills"], []))
        ):
            raise ApiError(
                422, "INVALID_INPUT", "Choose skills from the selected role."
            )
        connection.execute(
            "UPDATE profile SET display_name=%s,role_id=%s,weekly_goal=%s,education=%s,skills=%s,"
            "timezone=%s,retention_days=%s,consent_version=%s,updated_at=CURRENT_TIMESTAMP WHERE user_id=%s",
            (
                str(value["displayName"]).strip()[:100] or "Student",
                value["roleId"],
                int(value["weeklyGoal"]),
                str(value.get("education", ""))[:500],
                dumps(value.get("skills", [])),
                value.get("timezone", "Asia/Kolkata"),
                int(value["retentionDays"]),
                value.get("consentVersion") or None,
                user_id,
            ),
        )
        return profile_row(connection, user_id)


@app.get("/api/v1/catalog")
def catalog(_: User):
    with transaction() as connection:
        roles = [
            {"id": row["id"], "name": row["name"], "skills": loads(row["skills"], [])}
            for row in all_rows(connection, "SELECT * FROM role_catalog ORDER BY id")
        ]
        topics = [
            {"id": row["id"], "subject": row["subject"], "name": row["name"]}
            for row in all_rows(connection, "SELECT * FROM topic ORDER BY subject,id")
        ]
    return {
        "roles": roles,
        "topics": topics,
        "subjects": ["DSA", "DBMS", "OS"],
        "difficulties": ["EASY", "MEDIUM", "HARD"],
        "englishPrompt": {
            "version": "intro-v1",
            "text": "Introduce yourself, describe one project and your contribution, and explain your career goal.",
        },
    }


@app.post("/api/v1/tests/attempts")
def create_test(
    body: dict,
    user_id: User,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
):
    subject, difficulty, count = (
        body.get("subject"),
        body.get("difficulty"),
        body.get("count"),
    )
    topic = body.get("topicId") or ""
    if (
        subject not in ("DSA", "DBMS", "OS")
        or difficulty not in ("EASY", "MEDIUM", "HARD")
        or count not in (5, 10, 15)
    ):
        raise ApiError(422, "INVALID_INPUT", "Invalid test settings.")
    context = {
        "subject": subject,
        "topicId": topic,
        "difficulty": difficulty,
        "count": count,
    }
    with transaction() as connection:
        activity = create_activity(
            connection, user_id, "TEST", idempotency_key, context
        )
        exists = one(
            connection,
            "SELECT activity_id FROM test_attempt WHERE activity_id=%s",
            (activity["id"],),
            required=False,
        )
        if not exists:
            rows = all_rows(
                connection,
                "SELECT q.* FROM question q JOIN topic t ON t.id=q.topic_id WHERE q.state='PUBLISHED' "
                "AND t.subject=%s AND q.difficulty=%s AND (%s='' OR q.topic_id=%s) ORDER BY q.id",
                (subject, difficulty, topic, topic),
            )
            if len(rows) < count:
                raise ApiError(
                    422,
                    "CONTENT_UNAVAILABLE",
                    "Not enough reviewed questions match these settings.",
                )
            random.SystemRandom().shuffle(rows)
            connection.execute(
                "INSERT INTO test_attempt(activity_id,deadline) VALUES(%s,%s)",
                (activity["id"], now() + timedelta(minutes=count * 2)),
            )
            for position, question in enumerate(rows[:count]):
                connection.execute(
                    "INSERT INTO test_item(id,activity_id,question_id,position) VALUES(%s,%s,%s,%s)",
                    (str(uuid.uuid4()), activity["id"], question["id"], position),
                )
        return test_view(connection, user_id, activity["id"])


def test_view(connection, user_id: str, activity_id: str):
    activity = owned_activity(connection, user_id, activity_id)
    if activity["module"] != "TEST":
        raise ApiError(404, "NOT_FOUND", "The requested test was not found.")
    attempt = one(
        connection,
        "SELECT deadline FROM test_attempt WHERE activity_id=%s",
        (activity_id,),
    )
    rows = all_rows(
        connection,
        "SELECT i.id item_id,i.position,i.response,i.marked,i.version,q.id,q.topic_id,q.type,q.difficulty,q.prompt,q.options "
        "FROM test_item i JOIN question q ON q.id=i.question_id WHERE i.activity_id=%s ORDER BY i.position",
        (activity_id,),
    )
    items = [
        {
            "id": row["id"],
            "itemId": row["item_id"],
            "topicId": row["topic_id"],
            "type": row["type"],
            "difficulty": row["difficulty"],
            "prompt": row["prompt"],
            "options": loads(row["options"], []),
            "position": row["position"],
            "response": row["response"],
            "marked": row["marked"],
            "version": row["version"],
        }
        for row in rows
    ]
    return {
        "activity": activity_view(activity),
        "deadline": attempt["deadline"],
        "serverTime": now(),
        "items": items,
    }


@app.get("/api/v1/tests/attempts/{activity_id}")
def get_test(activity_id: str, user_id: User):
    with transaction() as connection:
        return test_view(connection, user_id, activity_id)


@app.put("/api/v1/tests/attempts/{activity_id}/responses/{item_id}")
def save_test_response(activity_id: str, item_id: str, body: dict, user_id: User):
    answer = body.get("answer") or ""
    if len(answer) > 6000:
        raise ApiError(422, "INVALID_INPUT", "Answer is too long.")
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        attempt = one(
            connection,
            "SELECT deadline FROM test_attempt WHERE activity_id=%s",
            (activity_id,),
        )
        if activity["state"] != "ACTIVE" or now() >= attempt["deadline"]:
            raise ApiError(
                409, "TEST_CLOSED", "The test is no longer accepting answers."
            )
        item = one(
            connection,
            "SELECT i.*,q.options,q.type FROM test_item i JOIN question q ON q.id=i.question_id "
            "WHERE i.id=%s AND i.activity_id=%s",
            (item_id, activity_id),
        )
        if (
            item["type"] != "SHORT_ANSWER"
            and answer
            and answer not in loads(item["options"], [])
        ):
            raise ApiError(422, "INVALID_INPUT", "Choose an option from this question.")
        expected = int(body.get("version", -1))
        if item["version"] != expected:
            if (
                item["version"] == expected + 1
                and item["response"] == answer
                and item["marked"] == bool(body.get("marked"))
            ):
                return {"version": item["version"], "saved": True}
            raise ApiError(409, "STALE_ANSWER", "Answer changed. Reload before saving.")
        connection.execute(
            "UPDATE test_item SET response=%s,marked=%s,version=version+1 WHERE id=%s",
            (answer, bool(body.get("marked")), item_id),
        )
        return {"version": expected + 1, "saved": True}


@app.post("/api/v1/tests/attempts/{activity_id}/submit")
def submit_test(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        if activity["state"] == "COMPLETED":
            return {"activityId": activity_id, "state": "COMPLETED"}
        job = enqueue_job(
            connection, user_id, "GRADE_TEST", activity_id, {"activityId": activity_id}
        )
        connection.execute(
            "UPDATE activity SET state='PROCESSING' WHERE id=%s", (activity_id,)
        )
        return {"activityId": activity_id, "jobId": job["id"], "state": "PROCESSING"}


def process_test(user_id: str, activity_id: str, job_id: str):
    try:
        with transaction() as connection:
            connection.execute("UPDATE job SET state='RUNNING' WHERE id=%s", (job_id,))
            owned_activity(connection, user_id, activity_id, True)
            rows = all_rows(
                connection,
                "SELECT i.*,q.answer,q.explanation,q.topic_id,q.prompt,q.type,q.criteria "
                "FROM test_item i JOIN question q ON q.id=i.question_id WHERE i.activity_id=%s ORDER BY i.position",
                (activity_id,),
            )
        for row in rows:
            if row["grading_status"] not in ("UNANSWERED", "PENDING"):
                continue
            response = (row["response"] or "").strip()
            if not response:
                points, status, feedback = (
                    0.0,
                    "UNANSWERED",
                    {"reason": "No answer submitted."},
                )
            elif row["type"] != "SHORT_ANSWER":
                correct = response == row["answer"]
                points, status = (100.0 if correct else 0.0), "OBJECTIVE"
                feedback = {"correct": correct, "explanation": row["explanation"]}
            else:
                try:
                    evaluation = ai.evaluate(
                        "SHORT_ANSWER", row["prompt"], row["answer"], response
                    )
                    points = (
                        ai.score("SHORT_ANSWER", evaluation["dimensions"])
                        if evaluation["scorable"]
                        else 0.0
                    )
                    status, feedback = "PROVISIONAL", evaluation
                except Exception:
                    points, status, feedback = (
                        None,
                        "PENDING",
                        {"reason": "AI review unavailable; human review is pending."},
                    )
            with transaction() as connection:
                connection.execute(
                    "UPDATE test_item SET points=%s,grading_status=%s,feedback=%s WHERE id=%s",
                    (points, status, dumps(feedback), row["id"]),
                )
        with transaction() as connection:
            activity = owned_activity(connection, user_id, activity_id, True)
            build_test_report(connection, activity)
        mark_job(job_id, "COMPLETED")
    except Exception as error:
        mark_job(job_id, "FAILED", type(error).__name__)


def build_test_report(connection, activity: dict):
    rows = all_rows(
        connection,
        "SELECT i.*,q.answer,q.explanation,q.topic_id,q.prompt,q.type FROM test_item i "
        "JOIN question q ON q.id=i.question_id WHERE i.activity_id=%s ORDER BY i.position",
        (activity["id"],),
    )
    details, by_topic, pending, objective_points = [], {}, 0, []
    for row in rows:
        if row["grading_status"] == "PENDING":
            pending += 1
        if row["type"] != "SHORT_ANSWER" and row["points"] is not None:
            objective_points.append(float(row["points"]))
        if row["points"] is not None:
            by_topic.setdefault(row["topic_id"], []).append(float(row["points"]))
        details.append(
            {
                "itemId": row["id"],
                "question": row["prompt"],
                "answer": row["response"],
                "correct": row["points"] == 100
                if row["type"] != "SHORT_ANSWER"
                else None,
                "points": row["points"],
                "status": row["grading_status"],
                "feedback": loads(row["feedback"], {}),
                "explanation": row["explanation"],
            }
        )
    score = (
        None
        if pending
        else round(sum(float(row["points"] or 0) for row in rows) / len(rows), 2)
    )
    report = {
        "module": "TEST",
        "score": score,
        "items": details,
        "pending": pending,
        "objectiveSubtotal": round(sum(objective_points) / len(objective_points), 2)
        if objective_points
        else None,
        "rubricVersion": "test-v1",
    }
    competencies = (
        {topic: sum(values) / len(values) for topic, values in by_topic.items()}
        if score is not None
        else {}
    )
    complete_activity(connection, activity, report, score, competencies)


@app.get("/api/v1/tests/attempts/{activity_id}/result")
def test_result(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )


def require_consent(connection, user_id: str):
    if (
        one(
            connection,
            "SELECT consent_version FROM profile WHERE user_id=%s",
            (user_id,),
        )["consent_version"]
        != "privacy-v1"
    ):
        raise ApiError(
            422,
            "CONSENT_REQUIRED",
            "Review and save the practice consent in Profile first.",
        )


def interview_view(connection, user_id: str, activity_id: str):
    activity = owned_activity(connection, user_id, activity_id)
    if activity["module"] != "INTERVIEW":
        raise ApiError(404, "NOT_FOUND", "The interview was not found.")
    session = one(
        connection,
        "SELECT * FROM interview_session WHERE activity_id=%s",
        (activity_id,),
    )
    remaining = session["remaining_seconds"]
    if session["current_started"] and activity["state"] == "ACTIVE":
        remaining = max(
            0, remaining - int((now() - session["current_started"]).total_seconds())
        )
    turns = all_rows(
        connection,
        "SELECT sequence,prompt,topic,kind,answer FROM interview_turn "
        "WHERE activity_id=%s ORDER BY sequence",
        (activity_id,),
    )
    return {
        "activity": activity_view(activity),
        "turns": [
            {
                "sequence": row["sequence"],
                "question": row["prompt"],
                "topic": row["topic"],
                "kind": row["kind"],
                "answer": row["answer"],
            }
            for row in turns
        ],
        "sequence": session["current_sequence"],
        "remainingSeconds": remaining,
        "wallDeadline": session["wall_deadline"],
    }


@app.post("/api/v1/interviews")
def create_interview(
    body: dict,
    user_id: User,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
):
    role, skills = body.get("roleId"), body.get("skills") or []
    kind, difficulty, mode, minutes = (
        body.get("type"),
        body.get("difficulty"),
        body.get("answerMode"),
        body.get("minutes"),
    )
    if (
        kind not in ("TECHNICAL", "HR", "MIXED")
        or difficulty not in ("EASY", "MEDIUM", "HARD")
        or mode not in ("TEXT", "VOICE")
        or minutes not in (10, 20, 30)
        or not skills
    ):
        raise ApiError(422, "INVALID_INPUT", "Check interview settings.")
    context = {
        "roleId": role,
        "skills": skills,
        "type": kind,
        "difficulty": difficulty,
        "answerMode": mode,
        "minutes": minutes,
    }
    with transaction() as connection:
        require_consent(connection, user_id)
        role_row = one(
            connection,
            "SELECT skills FROM role_catalog WHERE id=%s",
            (role,),
            required=False,
        )
        if not role_row or not set(skills).issubset(set(loads(role_row["skills"], []))):
            raise ApiError(422, "INVALID_INPUT", "Choose skills for the selected role.")
        activity = create_activity(
            connection, user_id, "INTERVIEW", idempotency_key, context
        )
        exists = one(
            connection,
            "SELECT activity_id FROM interview_session WHERE activity_id=%s",
            (activity["id"],),
            required=False,
        )
        if not exists:
            seeds = all_rows(
                connection,
                "SELECT * FROM interview_seed WHERE state='PUBLISHED' AND (role_id=%s OR role_id='shared') "
                "AND (%s='MIXED' OR kind=%s) ORDER BY id",
                (role, kind, kind),
            )
            seeds = [
                seed
                for seed in seeds
                if seed["kind"] == "HR" or seed["topic"] in skills
            ]
            if not seeds:
                raise ApiError(
                    422,
                    "CONTENT_UNAVAILABLE",
                    "No reviewed interview prompts match these settings.",
                )
            first = next(
                (
                    seed
                    for seed in seeds
                    if kind != "MIXED" or seed["kind"] == "TECHNICAL"
                ),
                seeds[0],
            )
            maximum = {10: 8, 20: 12, 30: 16}[minutes]
            connection.execute(
                "INSERT INTO interview_session(activity_id,remaining_seconds,max_questions,wall_deadline,current_started) "
                "VALUES(%s,%s,%s,%s,%s)",
                (
                    activity["id"],
                    minutes * 60,
                    maximum,
                    now() + timedelta(minutes=minutes * 3),
                    now(),
                ),
            )
            connection.execute(
                "INSERT INTO interview_turn(id,activity_id,sequence,topic,kind,prompt,reference_answer,followup_depth) "
                "VALUES(%s,%s,0,%s,%s,%s,%s,0)",
                (
                    str(uuid.uuid4()),
                    activity["id"],
                    first["topic"],
                    first["kind"],
                    first["prompt"],
                    first["reference_answer"],
                ),
            )
        return interview_view(connection, user_id, activity["id"])


@app.get("/api/v1/interviews/{activity_id}")
def get_interview(activity_id: str, user_id: User):
    with transaction() as connection:
        return interview_view(connection, user_id, activity_id)


@app.post("/api/v1/interviews/{activity_id}/answers")
def answer_interview(activity_id: str, body: dict, user_id: User):
    sequence, answer = body.get("sequence"), (body.get("text") or "").strip()
    submission = body.get("submissionKey")
    if (
        not isinstance(sequence, int)
        or not answer
        or len(answer) > 8000
        or not submission
        or len(submission) > 100
    ):
        raise ApiError(
            422, "INVALID_INPUT", "An answer and submission key are required."
        )
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        turn = one(
            connection,
            "SELECT * FROM interview_turn WHERE activity_id=%s AND sequence=%s",
            (activity_id, sequence),
        )
        if turn["answer"] is not None:
            if turn["answer"] != answer or turn["submission_key"] != submission:
                raise ApiError(
                    409,
                    "ANSWER_EXISTS",
                    "This question already has an accepted answer.",
                )
        else:
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
            if activity["state"] != "ACTIVE" or sequence != session["current_sequence"]:
                raise ApiError(
                    409,
                    "INTERVIEW_STATE",
                    "Wait for the next question or reload this interview.",
                )
            elapsed = int((now() - session["current_started"]).total_seconds())
            remaining = session["remaining_seconds"] - max(0, elapsed)
            if remaining <= 0 or now() >= session["wall_deadline"]:
                raise ApiError(
                    409,
                    "INTERVIEW_EXPIRED",
                    "Interview time has expired. Finish for your report.",
                )
            connection.execute(
                "UPDATE interview_turn SET answer=%s,media_id=%s,submission_key=%s WHERE id=%s",
                (answer, body.get("mediaId"), submission, turn["id"]),
            )
            connection.execute(
                "UPDATE interview_session SET remaining_seconds=%s,current_started=NULL WHERE activity_id=%s",
                (remaining, activity_id),
            )
            connection.execute(
                "UPDATE activity SET state='PROCESSING' WHERE id=%s", (activity_id,)
            )
        job = enqueue_job(
            connection,
            user_id,
            "INTERVIEW_ANSWER",
            activity_id,
            {"activityId": activity_id, "sequence": sequence},
        )
        if job["state"] in ("FAILED", "QUEUED"):
            connection.execute(
                "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s",
                (job["id"],),
            )
        return {"activityId": activity_id, "jobId": job["id"]}


def process_interview_answer(
    user_id: str, activity_id: str, sequence: int, job_id: str
):
    try:
        with transaction() as connection:
            connection.execute("UPDATE job SET state='RUNNING' WHERE id=%s", (job_id,))
            activity = owned_activity(connection, user_id, activity_id, True)
            turn = one(
                connection,
                "SELECT * FROM interview_turn WHERE activity_id=%s AND sequence=%s",
                (activity_id, sequence),
            )
        evaluation = (
            loads(turn["evaluation"])
            if turn["evaluation"]
            else ai.evaluate(
                turn["kind"], turn["prompt"], turn["reference_answer"], turn["answer"]
            )
        )
        with transaction() as connection:
            activity = owned_activity(connection, user_id, activity_id, True)
            connection.execute(
                "UPDATE interview_turn SET evaluation=%s WHERE id=%s",
                (dumps(evaluation), turn["id"]),
            )
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
            if (
                activity["state"] == "FINISHING"
                or sequence + 1 >= session["max_questions"]
                or now() >= session["wall_deadline"]
            ):
                build_interview_report(connection, activity)
            else:
                depth = turn["followup_depth"]
                next_turn = None
                if evaluation["scorable"] and depth < 2:
                    prompt = ai.follow_up(
                        turn["kind"],
                        turn["topic"],
                        turn["prompt"],
                        turn["answer"],
                        turn["reference_answer"],
                        depth + 1,
                    )
                    next_turn = (
                        turn["topic"],
                        turn["kind"],
                        prompt,
                        turn["reference_answer"],
                        depth + 1,
                    )
                else:
                    context = loads(activity["context"], {})
                    seen = {
                        row["prompt"]
                        for row in all_rows(
                            connection,
                            "SELECT prompt FROM interview_turn WHERE activity_id=%s",
                            (activity_id,),
                        )
                    }
                    seeds = all_rows(
                        connection,
                        "SELECT * FROM interview_seed WHERE state='PUBLISHED' AND (role_id=%s OR role_id='shared') "
                        "AND (%s='MIXED' OR kind=%s) ORDER BY id",
                        (context["roleId"], context["type"], context["type"]),
                    )
                    available = [
                        seed
                        for seed in seeds
                        if seed["prompt"] not in seen
                        and (seed["kind"] == "HR" or seed["topic"] in context["skills"])
                    ]
                    if available:
                        seed = next(
                            (
                                item
                                for item in available
                                if context["type"] != "MIXED"
                                or item["kind"] != turn["kind"]
                            ),
                            available[0],
                        )
                        next_turn = (
                            seed["topic"],
                            seed["kind"],
                            seed["prompt"],
                            seed["reference_answer"],
                            0,
                        )
                if next_turn is None:
                    build_interview_report(connection, activity)
                else:
                    connection.execute(
                        "INSERT INTO interview_turn(id,activity_id,sequence,topic,kind,prompt,reference_answer,followup_depth) "
                        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
                        (str(uuid.uuid4()), activity_id, sequence + 1, *next_turn),
                    )
                    connection.execute(
                        "UPDATE interview_session SET current_sequence=%s,current_started=%s WHERE activity_id=%s",
                        (sequence + 1, now(), activity_id),
                    )
                    connection.execute(
                        "UPDATE activity SET state='ACTIVE' WHERE id=%s", (activity_id,)
                    )
        mark_job(job_id, "COMPLETED")
    except Exception as error:
        mark_job(job_id, "FAILED", type(error).__name__)


def build_interview_report(connection, activity: dict):
    turns = all_rows(
        connection,
        "SELECT * FROM interview_turn WHERE activity_id=%s AND answer IS NOT NULL ORDER BY sequence",
        (activity["id"],),
    )
    if any(not row["evaluation"] for row in turns):
        raise RuntimeError("Answers are still being evaluated")
    details, scores, strengths, improvements, by_topic = [], [], [], [], {}
    for row in turns:
        evaluation = loads(row["evaluation"])
        details.append(
            {
                "question": row["prompt"],
                "answer": row["answer"],
                "evaluation": evaluation,
            }
        )
        strengths.extend(evaluation["strengths"])
        improvements.extend(evaluation["improvements"])
        if evaluation["scorable"]:
            value = ai.score(row["kind"], evaluation["dimensions"])
            scores.append(value)
            by_topic.setdefault(row["topic"], []).append(value)
    score = round(sum(scores) / len(scores), 2) if scores else None
    report = {
        "module": "INTERVIEW",
        "score": score,
        "strengths": list(dict.fromkeys(strengths))[:3],
        "improvements": list(dict.fromkeys(improvements))[:3],
        "answers": details,
        "rubricVersion": "rubric-v1",
    }
    competencies = {
        topic: sum(values) / len(values) for topic, values in by_topic.items()
    }
    complete_activity(connection, activity, report, score, competencies)


@app.post("/api/v1/interviews/{activity_id}/finish")
def finish_interview(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        if activity["state"] == "COMPLETED":
            return {"activityId": activity_id, "state": "COMPLETED"}
        pending = one(
            connection,
            "SELECT sequence FROM interview_turn WHERE activity_id=%s AND answer IS NOT NULL "
            "AND evaluation IS NULL ORDER BY sequence LIMIT 1",
            (activity_id,),
            required=False,
        )
        if pending:
            job = enqueue_job(
                connection,
                user_id,
                "INTERVIEW_ANSWER",
                activity_id,
                {"activityId": activity_id, "sequence": pending["sequence"]},
            )
            connection.execute(
                "UPDATE activity SET state='FINISHING' WHERE id=%s", (activity_id,)
            )
            return {"activityId": activity_id, "jobId": job["id"]}
        build_interview_report(connection, activity)
        return {"activityId": activity_id, "state": "COMPLETED"}


@app.get("/api/v1/interviews/{activity_id}/report")
def interview_report(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )


@app.get("/api/v1/jobs")
def list_jobs(activityId: str, user_id: User):
    with transaction() as connection:
        return [
            job_view(row)
            for row in all_rows(
                connection,
                "SELECT * FROM job WHERE user_id=%s AND activity_id=%s ORDER BY created_at",
                (user_id, activityId),
            )
        ]


@app.get("/api/v1/jobs/{job_id}")
def get_job(job_id: str, user_id: User):
    with transaction() as connection:
        return job_view(
            one(
                connection,
                "SELECT * FROM job WHERE id=%s AND user_id=%s",
                (job_id, user_id),
            )
        )


@app.post("/api/v1/jobs/{job_id}/retry")
def retry_job(job_id: str, user_id: User):
    with transaction() as connection:
        job = one(
            connection,
            "SELECT * FROM job WHERE id=%s AND user_id=%s FOR UPDATE",
            (job_id, user_id),
        )
        if job["state"] != "FAILED":
            return job_view(job)
        connection.execute(
            "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s", (job_id,)
        )
        _worker_wake.set()
        return {**job_view(job), "state": "QUEUED", "errorCode": None}


def media_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "state": row["state"],
        "durationSeconds": row["duration_seconds"],
        "transcript": row["transcript"],
        "expiresAt": row["delete_after"],
    }


def validate_wav(path, size: int) -> float:
    if size < 32044 or size > 6_000_000:
        raise ApiError(
            422, "INVALID_AUDIO", "Record between one second and three minutes."
        )
    try:
        with wave.open(str(path), "rb") as audio_file:
            if (
                audio_file.getnchannels() != 1
                or audio_file.getframerate() != 16000
                or audio_file.getsampwidth() != 2
            ):
                raise ApiError(
                    422, "INVALID_AUDIO", "Use mono 16 kHz 16-bit WAV audio."
                )
            frames = audio_file.getnframes()
            duration = frames / 16000
            samples = audio_file.readframes(min(frames, 16000))
        if not 1 <= duration <= 180 or max(samples, default=0) == min(
            samples, default=0
        ):
            raise ApiError(
                422, "INVALID_AUDIO", "No clear speech was detected. Record again."
            )
        return duration
    except (wave.Error, EOFError) as error:
        raise ApiError(
            422, "INVALID_AUDIO", "The recording is not a valid WAV file."
        ) from error


@app.post("/api/v1/media")
def upload_media(user_id: User, file: UploadFile = File(...)):
    media_id = str(uuid.uuid4())
    destination = config.MEDIA_ROOT / f"{media_id}.wav"
    digest = hashlib.sha256()
    size = 0
    with destination.open("wb") as output:
        while chunk := file.file.read(64 * 1024):
            size += len(chunk)
            if size > 6_000_000:
                output.close()
                destination.unlink(missing_ok=True)
                raise ApiError(422, "INVALID_AUDIO", "The recording is too large.")
            digest.update(chunk)
            output.write(chunk)
    try:
        duration = validate_wav(destination, size)
        with transaction() as connection:
            existing = one(
                connection,
                "SELECT * FROM media WHERE user_id=%s AND checksum=%s",
                (user_id, digest.hexdigest()),
                required=False,
            )
            if existing:
                destination.unlink(missing_ok=True)
                job = enqueue_job(
                    connection,
                    user_id,
                    "TRANSCRIBE",
                    existing["id"],
                    {"mediaId": existing["id"]},
                )
                return {"media": media_view(existing), "jobId": job["id"]}
            retention = one(
                connection,
                "SELECT retention_days FROM profile WHERE user_id=%s",
                (user_id,),
            )["retention_days"]
            connection.execute(
                "INSERT INTO media(id,user_id,storage_key,bytes,duration_seconds,checksum,state,delete_after) "
                "VALUES(%s,%s,%s,%s,%s,%s,'PROCESSING',%s)",
                (
                    media_id,
                    user_id,
                    destination.name,
                    size,
                    duration,
                    digest.hexdigest(),
                    now() + timedelta(days=retention),
                ),
            )
            row = one(connection, "SELECT * FROM media WHERE id=%s", (media_id,))
            job = enqueue_job(
                connection, user_id, "TRANSCRIBE", media_id, {"mediaId": media_id}
            )
        return {"media": media_view(row), "jobId": job["id"]}
    except Exception:
        if destination.exists():
            destination.unlink(missing_ok=True)
        raise


def process_transcription(user_id: str, media_id: str, job_id: str):
    try:
        with transaction() as connection:
            row = one(
                connection,
                "SELECT * FROM media WHERE id=%s AND user_id=%s",
                (media_id, user_id),
            )
            connection.execute("UPDATE job SET state='RUNNING' WHERE id=%s", (job_id,))
        transcript = ai.transcribe(config.MEDIA_ROOT / row["storage_key"])
        with transaction() as connection:
            connection.execute(
                "UPDATE media SET transcript=%s,state='READY' WHERE id=%s AND state='PROCESSING'",
                (transcript, media_id),
            )
        mark_job(job_id, "COMPLETED")
    except Exception as error:
        mark_job(job_id, "FAILED", type(error).__name__)


@app.get("/api/v1/media/{media_id}")
def get_media(media_id: str, user_id: User):
    with transaction() as connection:
        return media_view(
            one(
                connection,
                "SELECT * FROM media WHERE id=%s AND user_id=%s",
                (media_id, user_id),
            )
        )


@app.get("/api/v1/media/{media_id}/audio")
def get_audio(media_id: str, user_id: User):
    with transaction() as connection:
        row = one(
            connection,
            "SELECT * FROM media WHERE id=%s AND user_id=%s",
            (media_id, user_id),
        )
    return FileResponse(
        config.MEDIA_ROOT / row["storage_key"],
        media_type="audio/wav",
        headers={"Cache-Control": "no-store"},
    )


@app.delete("/api/v1/media/{media_id}")
def delete_media(media_id: str, user_id: User):
    with transaction() as connection:
        row = one(
            connection,
            "SELECT * FROM media WHERE id=%s AND user_id=%s FOR UPDATE",
            (media_id, user_id),
        )
        connection.execute(
            "UPDATE media SET state='DELETED',transcript=NULL WHERE id=%s", (media_id,)
        )
    (config.MEDIA_ROOT / row["storage_key"]).unlink(missing_ok=True)
    return {"deleted": True}


@app.post("/api/v1/english/attempts")
def create_english(
    body: dict,
    user_id: User,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
):
    previous = body.get("previousId") or ""
    with transaction() as connection:
        require_consent(connection, user_id)
        group = str(uuid.uuid4())
        if previous:
            prior = owned_activity(connection, user_id, previous)
            if prior["module"] != "ENGLISH":
                raise ApiError(404, "NOT_FOUND", "The previous attempt was not found.")
            group = one(
                connection,
                "SELECT group_id FROM english_attempt WHERE activity_id=%s",
                (previous,),
            )["group_id"]
        context = {
            "previousId": previous,
            "retryGroup": group,
            "promptVersion": "intro-v1",
        }
        activity = create_activity(
            connection, user_id, "ENGLISH", idempotency_key, context
        )
        if not one(
            connection,
            "SELECT activity_id FROM english_attempt WHERE activity_id=%s",
            (activity["id"],),
            required=False,
        ):
            connection.execute(
                "INSERT INTO english_attempt(activity_id,group_id,previous_id) VALUES(%s,%s,%s)",
                (activity["id"], group, previous or None),
            )
        return {"activity": activity_view(activity)}


@app.post("/api/v1/english/attempts/{activity_id}/submit")
def submit_english(activity_id: str, body: dict, user_id: User):
    with transaction() as connection:
        owned_activity(connection, user_id, activity_id, True)
        media = one(
            connection,
            "SELECT * FROM media WHERE id=%s AND user_id=%s",
            (body.get("mediaId"), user_id),
        )
        if media["state"] != "READY":
            raise ApiError(422, "MEDIA_PROCESSING", "Wait for transcription to finish.")
        confirmed = (body.get("transcript") or media["transcript"] or "").strip()
        if not confirmed or len(confirmed) > 8000:
            raise ApiError(
                422, "INVALID_INPUT", "Review the transcript before submitting."
            )
        connection.execute(
            "UPDATE english_attempt SET media_id=%s,raw_transcript=%s,confirmed_transcript=%s,edited=%s "
            "WHERE activity_id=%s",
            (
                media["id"],
                media["transcript"],
                confirmed,
                confirmed != media["transcript"],
                activity_id,
            ),
        )
        connection.execute(
            "UPDATE activity SET state='PROCESSING' WHERE id=%s", (activity_id,)
        )
        job = enqueue_job(
            connection,
            user_id,
            "ENGLISH_REPORT",
            activity_id,
            {"activityId": activity_id},
        )
        return {"activityId": activity_id, "jobId": job["id"]}


def process_english(user_id: str, activity_id: str, job_id: str):
    try:
        with transaction() as connection:
            connection.execute("UPDATE job SET state='RUNNING' WHERE id=%s", (job_id,))
            activity = owned_activity(connection, user_id, activity_id, True)
            attempt = one(
                connection,
                "SELECT e.*,m.duration_seconds FROM english_attempt e JOIN media m ON m.id=e.media_id "
                "WHERE e.activity_id=%s",
                (activity_id,),
            )
        prompt = "Introduce yourself, describe one project and your contribution, and explain your career goal."
        evaluation = ai.evaluate(
            "ENGLISH", prompt, prompt, attempt["confirmed_transcript"]
        )
        words = len(attempt["confirmed_transcript"].split())
        fillers = sum(
            attempt["confirmed_transcript"].lower().split().count(term)
            for term in ("um", "uh", "like")
        )
        result_score = (
            ai.score("ENGLISH", evaluation["dimensions"])
            if evaluation["scorable"]
            else None
        )
        metrics = {
            "wordCount": words,
            "speakingRate": round(words * 60 / attempt["duration_seconds"], 1),
            "fillerCount": fillers,
        }
        report = {
            "module": "ENGLISH",
            "score": result_score,
            "evaluation": evaluation,
            "transcript": attempt["confirmed_transcript"],
            "rawTranscript": attempt["raw_transcript"],
            "edited": attempt["edited"],
            "metrics": metrics,
            "rubricVersion": "rubric-v1",
        }
        with transaction() as connection:
            activity = owned_activity(connection, user_id, activity_id, True)
            previous = loads(activity["context"], {}).get("previousId")
            if previous:
                prior = one(
                    connection,
                    "SELECT score FROM activity WHERE id=%s AND user_id=%s",
                    (previous, user_id),
                    required=False,
                )
                if prior and prior["score"] is not None and result_score is not None:
                    report["change"] = round(result_score - prior["score"], 2)
            complete_activity(
                connection,
                activity,
                report,
                result_score,
                {
                    key: float(value) * 25
                    for key, value in evaluation["dimensions"].items()
                }
                if result_score is not None
                else {},
            )
        mark_job(job_id, "COMPLETED")
    except Exception as error:
        mark_job(job_id, "FAILED", type(error).__name__)


@app.get("/api/v1/english/attempts/{activity_id}/report")
def english_report(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )


def progress_summary(connection, user_id: str) -> dict:
    rows = all_rows(
        connection,
        "SELECT * FROM progress_activity WHERE user_id=%s ORDER BY completed_at DESC,activity_id",
        (user_id,),
    )
    seen, scores, evidence = set(), {}, {}
    for row in rows:
        if row["retry_group"] in seen:
            continue
        seen.add(row["retry_group"])
        module = row["module"]
        evidence[module] = evidence.get(module, 0) + 1
        if len(scores.setdefault(module, [])) < 5:
            scores[module].append(float(row["score"]))
    module_scores = {
        key: round(sum(values) / len(values), 1) for key, values in scores.items()
    }
    overall = None
    if all(key in module_scores for key in ("INTERVIEW", "TEST", "ENGLISH")):
        overall = round(
            module_scores["INTERVIEW"] * 0.4
            + module_scores["TEST"] * 0.4
            + module_scores["ENGLISH"] * 0.2,
            1,
        )
    profile = profile_row(connection, user_id)
    monday = (now() - timedelta(days=now().weekday())).date()
    weekly = sum(1 for row in rows if row["completed_at"].date() >= monday)
    label = (
        "Build your baseline"
        if overall is None
        else (
            "Practice score"
            if evidence and all(value >= 3 for value in evidence.values())
            else "Early estimate"
        )
    )
    return {
        "overallScore": overall,
        "moduleScores": module_scores,
        "evidenceCounts": evidence,
        "scoreLabel": label,
        "weeklyCompleted": weekly,
        "weeklyGoal": profile["weeklyGoal"],
        "scoringVersion": "v1",
        "eligibleActivities": len(rows),
        "weights": {"INTERVIEW": 40, "TEST": 40, "ENGLISH": 20},
    }


def competencies(connection, user_id: str) -> list[dict]:
    rows = all_rows(
        connection,
        "SELECT e.competency,e.score,p.completed_at,p.retry_group FROM competency_evidence e "
        "JOIN progress_activity p ON p.activity_id=e.activity_id WHERE p.user_id=%s "
        "ORDER BY p.completed_at DESC",
        (user_id,),
    )
    seen, grouped, dates = set(), {}, {}
    for row in rows:
        marker = (row["competency"], row["retry_group"])
        if marker in seen:
            continue
        seen.add(marker)
        values = grouped.setdefault(row["competency"], [])
        if len(values) < 5:
            values.append(float(row["score"]))
        dates.setdefault(row["competency"], row["completed_at"])
    return [
        {
            "id": key,
            "score": round(sum(values) / len(values), 1),
            "evidenceCount": len(values),
            "lastAssessedAt": dates[key],
            "needsRefresh": dates[key] < now() - timedelta(days=30),
        }
        for key, values in sorted(grouped.items())
    ]


def recommendations(connection, user_id: str) -> list[dict]:
    summary = progress_summary(connection, user_id)
    dismissed = {
        row["rule_key"]
        for row in all_rows(
            connection,
            "SELECT rule_key FROM recommendation_dismissal WHERE user_id=%s AND until_at>CURRENT_TIMESTAMP",
            (user_id,),
        )
    }
    result = []
    for module in ("TEST", "INTERVIEW", "ENGLISH"):
        key = f"baseline:{module}"
        if module not in summary["moduleScores"] and key not in dismissed:
            result.append(
                {
                    "id": key,
                    "module": module,
                    "topicId": "",
                    "title": f"Build your {module.lower()} baseline",
                    "reason": "Complete one activity to establish an initial score.",
                    "difficulty": "EASY",
                    "ruleVersion": "v1",
                }
            )
    for item in sorted(
        competencies(connection, user_id), key=lambda value: value["score"]
    ):
        key = "topic:" + item["id"]
        if key in dismissed:
            continue
        identifier = item["id"]
        module = (
            "ENGLISH"
            if identifier.startswith("english-")
            else (
                "TEST"
                if identifier.startswith(("dsa-", "dbms-", "os-"))
                else "INTERVIEW"
            )
        )
        result.append(
            {
                "id": key,
                "module": module,
                "topicId": identifier,
                "title": "Practise " + identifier.replace("-", " "),
                "reason": "Recent evidence suggests this is your best next practice topic.",
                "difficulty": "EASY" if item["score"] < 60 else "MEDIUM",
                "ruleVersion": "v1",
            }
        )
    return result[:3]


@app.get("/api/v1/dashboard")
def dashboard(user_id: User):
    with transaction() as connection:
        return {
            **progress_summary(connection, user_id),
            "displayName": profile_row(connection, user_id)["displayName"],
            "recommendations": recommendations(connection, user_id),
        }


@app.get("/api/v1/progress")
def progress(user_id: User, days: int = 30):
    if not 1 <= days <= 365:
        raise ApiError(422, "INVALID_INPUT", "Choose a period from 1 to 365 days.")
    with transaction() as connection:
        timeline = [
            {"at": row["created_at"], "summary": loads(row["summary"], {})}
            for row in all_rows(
                connection,
                "SELECT summary,created_at FROM progress_snapshot WHERE user_id=%s AND created_at>=%s ORDER BY created_at",
                (user_id, now() - timedelta(days=days)),
            )
        ]
        return {"summary": progress_summary(connection, user_id), "timeline": timeline}


@app.get("/api/v1/competencies")
def get_competencies(user_id: User):
    with transaction() as connection:
        return competencies(connection, user_id)


@app.get("/api/v1/activities")
def activities(user_id: User, module: str = ""):
    with transaction() as connection:
        rows = all_rows(
            connection,
            "SELECT * FROM activity WHERE user_id=%s ORDER BY created_at DESC",
            (user_id,),
        )
        return [
            {
                "id": row["id"],
                "module": row["module"],
                "state": row["state"],
                "score": row["score"],
                "createdAt": row["created_at"],
                "completedAt": row["completed_at"],
            }
            for row in rows
            if not module or row["module"] == module
        ]


@app.get("/api/v1/recommendations")
def get_recommendations(user_id: User):
    with transaction() as connection:
        return recommendations(connection, user_id)


@app.patch("/api/v1/recommendations/{rule_id:path}")
def dismiss_recommendation(rule_id: str, user_id: User):
    with transaction() as connection:
        if rule_id not in {item["id"] for item in recommendations(connection, user_id)}:
            raise ApiError(422, "INVALID_INPUT", "Unknown recommendation.")
        connection.execute(
            "INSERT INTO recommendation_dismissal(user_id,rule_key,until_at) VALUES(%s,%s,%s) "
            "ON CONFLICT(user_id,rule_key) DO UPDATE SET until_at=EXCLUDED.until_at",
            (user_id, rule_id, now() + timedelta(days=3)),
        )
    return {"dismissed": True}


def require_recent_auth(connection, user_id: str):
    recent = one(
        connection,
        "SELECT 1 ok FROM login_session WHERE user_id=%s AND authenticated_at>=%s LIMIT 1",
        (user_id, now() - timedelta(minutes=5)),
        required=False,
    )
    if not recent:
        raise ApiError(401, "REAUTHENTICATION_REQUIRED", "Confirm your password again.")


@app.post("/api/v1/me/exports")
def export_account(background: BackgroundTasks, user_id: User):
    with transaction() as connection:
        require_recent_auth(connection, user_id)
        profile = profile_row(connection, user_id)
        activity_rows = all_rows(
            connection,
            "SELECT * FROM activity WHERE user_id=%s ORDER BY created_at",
            (user_id,),
        )
        media_rows = all_rows(
            connection,
            "SELECT * FROM media WHERE user_id=%s AND state!='DELETED'",
            (user_id,),
        )
        test_rows = all_rows(
            connection,
            "SELECT i.activity_id,i.position,q.prompt,q.type,i.response,i.marked,i.points,"
            "i.grading_status,i.feedback FROM test_item i JOIN activity a ON a.id=i.activity_id "
            "JOIN question q ON q.id=i.question_id WHERE a.user_id=%s ORDER BY a.created_at,i.position",
            (user_id,),
        )
        interview_rows = all_rows(
            connection,
            "SELECT t.activity_id,t.sequence,t.topic,t.kind,t.prompt,t.answer,t.evaluation "
            "FROM interview_turn t JOIN activity a ON a.id=t.activity_id WHERE a.user_id=%s "
            "ORDER BY a.created_at,t.sequence",
            (user_id,),
        )
        english_rows = all_rows(
            connection,
            "SELECT e.* FROM english_attempt e JOIN activity a ON a.id=e.activity_id "
            "WHERE a.user_id=%s ORDER BY a.created_at",
            (user_id,),
        )
    temp = tempfile.NamedTemporaryFile(
        prefix="interviewedge-export-", suffix=".zip", delete=False
    )
    temp.close()
    with zipfile.ZipFile(temp.name, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("profile.json", json.dumps(profile, default=str, indent=2))
        archive.writestr(
            "activities.json",
            json.dumps(
                [activity_view(row) for row in activity_rows], default=str, indent=2
            ),
        )
        archive.writestr(
            "test-responses.json", json.dumps(test_rows, default=str, indent=2)
        )
        archive.writestr(
            "interview-answers.json",
            json.dumps(interview_rows, default=str, indent=2),
        )
        archive.writestr(
            "english-attempts.json", json.dumps(english_rows, default=str, indent=2)
        )
        archive.writestr(
            "recordings.json",
            json.dumps(
                [
                    {
                        "id": row["id"],
                        "durationSeconds": row["duration_seconds"],
                        "transcript": row["transcript"],
                        "createdAt": row["created_at"],
                        "deleteAfter": row["delete_after"],
                    }
                    for row in media_rows
                ],
                default=str,
                indent=2,
            ),
        )
        for row in media_rows:
            path = config.MEDIA_ROOT / row["storage_key"]
            if path.is_file():
                archive.write(path, f"recordings/{row['id']}.wav")
    background.add_task(os.unlink, temp.name)
    return FileResponse(
        temp.name, media_type="application/zip", filename="interviewedge-export.zip"
    )


@app.delete("/api/v1/me")
def delete_account(user_id: User):
    with transaction() as connection:
        require_recent_auth(connection, user_id)
        media_rows = all_rows(
            connection, "SELECT storage_key FROM media WHERE user_id=%s", (user_id,)
        )
        connection.execute("DELETE FROM edge_user WHERE id=%s", (user_id,))
    for row in media_rows:
        (config.MEDIA_ROOT / row["storage_key"]).unlink(missing_ok=True)
    return {"deleted": True}


def question_view(row: dict) -> dict:
    return {
        "id": row["id"],
        "familyId": row["family_id"],
        "version": row["version"],
        "topicId": row["topic_id"],
        "type": row["type"],
        "difficulty": row["difficulty"],
        "prompt": row["prompt"],
        "options": loads(row["options"], []),
        "answer": row["answer"],
        "explanation": row["explanation"],
        "criteria": loads(row["criteria"], []),
        "source": row["source"],
        "license": row["license"],
        "author": row["author"],
        "reviewer": row["reviewer"],
        "state": row["state"],
    }


def validate_question(connection, body: dict):
    required = (
        "topicId",
        "type",
        "difficulty",
        "prompt",
        "options",
        "answer",
        "explanation",
        "criteria",
        "source",
        "license",
    )
    if any(key not in body or body[key] is None for key in required):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "Question, answer, explanation, source and licence are required.",
        )
    if body["type"] not in ("MCQ", "CODE_OUTPUT", "SHORT_ANSWER") or body[
        "difficulty"
    ] not in ("EASY", "MEDIUM", "HARD"):
        raise ApiError(
            422, "INVALID_INPUT", "Unsupported question format or difficulty."
        )
    if not one(
        connection,
        "SELECT 1 ok FROM topic WHERE id=%s",
        (body["topicId"],),
        required=False,
    ):
        raise ApiError(422, "INVALID_INPUT", "Unknown topic.")
    if (
        not str(body["prompt"]).strip()
        or len(body["prompt"]) > 10_000
        or not str(body["answer"]).strip()
        or not str(body["explanation"]).strip()
        or not str(body["source"]).strip()
        or not str(body["license"]).strip()
    ):
        raise ApiError(422, "INVALID_INPUT", "Question fields cannot be blank.")
    options, criteria = body["options"], body["criteria"]
    if (
        not isinstance(options, list)
        or not isinstance(criteria, list)
        or any(not str(value).strip() for value in options + criteria)
    ):
        raise ApiError(
            422, "INVALID_INPUT", "Options and criteria must be non-blank lists."
        )
    if body["type"] != "SHORT_ANSWER" and (
        not 2 <= len(options) <= 6
        or len(set(options)) != len(options)
        or body["answer"] not in options
    ):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "Objective questions need distinct options containing the answer.",
        )
    if body["type"] == "SHORT_ANSWER" and not criteria:
        raise ApiError(422, "INVALID_INPUT", "Short answers require criteria.")


def create_question(connection, actor: str, body: dict):
    validate_question(connection, body)
    identifier = str(uuid.uuid4())
    family = body.get("familyId") or identifier
    if family != identifier:
        first = one(
            connection,
            "SELECT id FROM question WHERE family_id=%s AND version=1 FOR UPDATE",
            (family,),
            required=False,
        )
        if not first:
            raise ApiError(422, "INVALID_INPUT", "Unknown question family.")
    version = one(
        connection,
        "SELECT COALESCE(MAX(version),0)+1 next FROM question WHERE family_id=%s",
        (family,),
    )["next"]
    connection.execute(
        "INSERT INTO question(id,family_id,version,topic_id,type,difficulty,prompt,options,answer,explanation,criteria,source,license,author,state) "
        "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'DRAFT')",
        (
            identifier,
            family,
            version,
            body["topicId"],
            body["type"],
            body["difficulty"],
            body["prompt"],
            dumps(body["options"]),
            body["answer"],
            body["explanation"],
            dumps(body["criteria"]),
            body["source"],
            body["license"],
            actor,
        ),
    )
    return question_view(
        one(connection, "SELECT * FROM question WHERE id=%s", (identifier,))
    )


@app.get("/api/v1/admin/questions")
def admin_questions(_: Admin):
    with transaction() as connection:
        return [
            question_view(row)
            for row in all_rows(
                connection,
                "SELECT * FROM question ORDER BY topic_id,difficulty,version",
            )
        ]


@app.post("/api/v1/admin/questions")
def admin_create_question(body: dict, actor: Admin):
    with transaction() as connection:
        return create_question(connection, actor, body)


@app.post("/api/v1/admin/questions/import")
def admin_import_questions(body: list[dict], actor: Admin):
    if not 1 <= len(body) <= 300:
        raise ApiError(422, "INVALID_INPUT", "Import between 1 and 300 questions.")
    with transaction() as connection:
        for item in body:
            validate_question(connection, item)
        return [create_question(connection, actor, item) for item in body]


@app.post("/api/v1/admin/questions/{question_id}/publish")
def admin_publish_question(question_id: str, actor: Admin):
    with transaction() as connection:
        row = one(
            connection, "SELECT * FROM question WHERE id=%s FOR UPDATE", (question_id,)
        )
        validate_question(connection, question_view(row))
        if row["author"] == actor:
            raise ApiError(
                422,
                "REVIEW_REQUIRED",
                "A different reviewer must approve this question.",
            )
        if row["state"] != "DRAFT":
            raise ApiError(409, "STATE_CONFLICT", "Only a draft can be published.")
        connection.execute(
            "UPDATE question SET state='RETIRED' WHERE family_id=%s AND state='PUBLISHED'",
            (row["family_id"],),
        )
        connection.execute(
            "UPDATE question SET state='PUBLISHED',reviewer=%s WHERE id=%s",
            (actor, question_id),
        )
        return question_view(
            one(connection, "SELECT * FROM question WHERE id=%s", (question_id,))
        )


@app.post("/api/v1/admin/questions/{question_id}/retire")
def admin_retire_question(question_id: str, _: Admin):
    with transaction() as connection:
        if (
            connection.execute(
                "UPDATE question SET state='RETIRED' WHERE id=%s", (question_id,)
            ).rowcount
            != 1
        ):
            raise ApiError(404, "NOT_FOUND", "Question was not found.")
    return {"retired": True}


@app.get("/api/v1/admin/seeds")
def admin_seeds(_: Admin):
    with transaction() as connection:
        return [
            {
                "id": row["id"],
                "roleId": row["role_id"],
                "kind": row["kind"],
                "topic": row["topic"],
                "prompt": row["prompt"],
                "referenceAnswer": row["reference_answer"],
                "state": row["state"],
            }
            for row in all_rows(connection, "SELECT * FROM interview_seed ORDER BY id")
        ]


@app.post("/api/v1/admin/seeds/{seed_id}/publish")
def admin_publish_seed(seed_id: str, actor: Admin):
    with transaction() as connection:
        if (
            connection.execute(
                "UPDATE interview_seed SET state='PUBLISHED',reviewer=%s WHERE id=%s AND state='DRAFT'",
                (actor, seed_id),
            ).rowcount
            != 1
        ):
            raise ApiError(409, "STATE_CONFLICT", "Seed is not a draft.")
    return {"published": True}


@app.get("/api/v1/admin/reviews")
def admin_reviews(_: Admin):
    with transaction() as connection:
        return all_rows(
            connection,
            "SELECT i.id AS item_id,i.activity_id,i.response,i.feedback,q.prompt,q.answer,q.criteria "
            "FROM test_item i JOIN question q ON q.id=i.question_id WHERE i.grading_status IN ('PROVISIONAL','PENDING') "
            "ORDER BY i.activity_id",
        )


@app.post("/api/v1/admin/reviews/{activity_id}/{item_id}")
def admin_review(activity_id: str, item_id: str, body: dict, actor: Admin):
    points, reason = body.get("points"), str(body.get("reason") or "").strip()
    if (
        not isinstance(points, (int, float))
        or not 0 <= points <= 100
        or not reason
        or len(reason) > 2000
    ):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "A score from 0 to 100 and review reason are required.",
        )
    with transaction() as connection:
        changed = connection.execute(
            "UPDATE test_item SET points=%s,grading_status='REVIEWED',feedback=%s "
            "WHERE id=%s AND activity_id=%s AND grading_status IN ('PROVISIONAL','PENDING')",
            (
                points,
                dumps({"reviewer": actor, "reason": reason}),
                item_id,
                activity_id,
            ),
        ).rowcount
        if changed != 1:
            raise ApiError(409, "STATE_CONFLICT", "Item is not awaiting review.")
        activity = one(
            connection, "SELECT * FROM activity WHERE id=%s FOR UPDATE", (activity_id,)
        )
        build_test_report(connection, activity)
    return {"reviewed": True}


admin_directory = config.BACKEND_ROOT / "static" / "admin"
if admin_directory.is_dir():
    app.mount("/admin", StaticFiles(directory=admin_directory, html=True), name="admin")
