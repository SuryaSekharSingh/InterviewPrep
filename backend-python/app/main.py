"""Core API: assessment sessions, scoring, and durable background jobs.

Read-only progress, account data, and content administration routes are registered
from their feature modules below. Keep state transitions here so transaction and
job-finalization rules remain in one place.
"""

from __future__ import annotations

import hashlib
import random
import threading
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from datetime import timedelta
from typing import Annotated

from fastapi import Depends, FastAPI, Header, Request, Response
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import account, admin, ai, config, progress, security
from .admin import Admin
from .db import all_rows, one, run_migrations, transaction
from .errors import ApiError, install_error_handlers
from .progress import activity_view, profile_row, progress_summary
from .scoring import ScoringRequest, ScoringStatus
from .shared import dumps, loads, now

User = Annotated[str, Depends(security.current_user)]


# Shared activity finalization and job lifecycle.
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
    connection,
    row: dict,
    report: dict,
    score: float | None,
    competencies: dict,
    *,
    eligible: bool = True,
):
    completed = row.get("completed_at") or now()
    eligible = eligible and score is not None
    if (
        row["state"] == "COMPLETED"
        and loads(row.get("report")) == report
        and row["eligible"] == eligible
    ):
        return
    connection.execute(
        "UPDATE activity SET state='COMPLETED',report=%s,score=%s,eligible=%s,"
        "version=version+1,completed_at=%s WHERE id=%s",
        (dumps(report), score, eligible, completed, row["id"]),
    )
    # Regrading replaces evidence, including when an earlier score becomes ineligible.
    connection.execute(
        "DELETE FROM competency_evidence WHERE activity_id=%s", (row["id"],)
    )
    if not eligible:
        connection.execute(
            "DELETE FROM progress_activity WHERE activity_id=%s", (row["id"],)
        )
        connection.execute(
            "DELETE FROM progress_snapshot WHERE source_activity=%s", (row["id"],)
        )
    else:
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
    connection, user_id: str, kind: str, reference_id: str, payload: dict
) -> dict:
    dedupe = f"{kind.lower()}:{reference_id}:{payload.get('sequence', '')}"
    existing = one(
        connection, "SELECT * FROM job WHERE dedupe_key=%s", (dedupe,), required=False
    )
    if existing:
        return existing
    job_id = str(uuid.uuid4())
    connection.execute(
        "INSERT INTO job(id,user_id,kind,payload,dedupe_key,activity_id) VALUES(%s,%s,%s,%s,%s,%s)",
        (
            job_id,
            user_id,
            kind,
            dumps(payload),
            dedupe,
            reference_id,
        ),
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
_worker_kinds = ("GRADE_TEST", "INTERVIEW_ANSWER", "ENGLISH_REPORT")


def dispatch_job(job: dict):
    payload = loads(job["payload"], {})
    if job["kind"] == "GRADE_TEST":
        process_test(job["user_id"], payload["activityId"], job["id"])
    elif job["kind"] == "INTERVIEW_ANSWER":
        process_interview_answer(
            job["user_id"], payload["activityId"], payload["sequence"], job["id"]
        )
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


app = FastAPI(title="InterviewEdge local API", version="0.3.0", lifespan=lifespan)
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


# Authentication, profile, and catalogue.
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


@app.get("/api/v1/me")
def get_profile(user_id: User):
    with transaction() as connection:
        return profile_row(connection, user_id)


@app.patch("/api/v1/me")
def update_profile(body: dict, user_id: User):
    with transaction() as connection:
        current = profile_row(connection, user_id)
        value = {**current, **body}
        if not 1 <= int(value["weeklyGoal"]) <= 30:
            raise ApiError(422, "INVALID_INPUT", "Check the weekly goal.")
        if "retentionDays" in body:
            raise ApiError(
                422, "INVALID_INPUT", "Recording settings are no longer available."
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
            "timezone=%s,consent_version=%s,updated_at=CURRENT_TIMESTAMP WHERE user_id=%s",
            (
                str(value["displayName"]).strip()[:100] or "Student",
                value["roleId"],
                int(value["weeklyGoal"]),
                str(value.get("education", ""))[:500],
                dumps(value.get("skills", [])),
                value.get("timezone", "Asia/Kolkata"),
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
            "version": "intro-written-v1",
            "text": "Introduce yourself, describe one project and your contribution, and explain your career goal.",
        },
    }


# Subject tests: server-selected questions, saved answers, and scoring.
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
        if activity["module"] != "TEST":
            raise ApiError(404, "NOT_FOUND", "The test was not found.")
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
        evaluation_failed = False
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
                        "SHORT_ANSWER",
                        row["prompt"],
                        row["answer"],
                        response,
                        criteria=loads(row["criteria"], []),
                    )
                    points = (
                        ai.score("SHORT_ANSWER", evaluation["dimensions"])
                        if evaluation["scorable"]
                        else None
                    )
                    status, feedback = (
                        ("PROVISIONAL" if evaluation["scorable"] else "PENDING"),
                        evaluation,
                    )
                except Exception:
                    evaluation_failed = True
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
        mark_job(
            job_id,
            "FAILED" if evaluation_failed else "COMPLETED",
            "AI_EVALUATION_FAILED" if evaluation_failed else None,
        )
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
    provisional = any(row["grading_status"] == "PROVISIONAL" for row in rows)
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
        "provisional": provisional,
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
    complete_activity(
        connection, activity, report, score, competencies, eligible=not provisional
    )


@app.get("/api/v1/tests/attempts/{activity_id}/result")
def test_result(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )


# Interviews: persisted turns and follow-up evaluation.
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
        body.get("answerMode", "TEXT"),
        body.get("minutes"),
    )
    if (
        kind not in ("TECHNICAL", "HR", "MIXED")
        or difficulty not in ("EASY", "MEDIUM", "HARD")
        or mode != "TEXT"
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
        or "mediaId" in body
    ):
        raise ApiError(
            422, "INVALID_INPUT", "An answer and submission key are required."
        )
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        turn = one(
            connection,
            "SELECT * FROM interview_turn WHERE activity_id=%s AND sequence=%s FOR UPDATE",
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
                "SELECT * FROM interview_session WHERE activity_id=%s FOR UPDATE",
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
                "UPDATE interview_turn SET answer=%s,submission_key=%s WHERE id=%s",
                (answer, submission, turn["id"]),
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
            if job["state"] == "FAILED" and job["attempts"] >= 5:
                raise ApiError(
                    409,
                    "RETRY_LIMIT",
                    "The retry limit was reached. Contact the administrator.",
                )
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
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
        if activity["state"] == "COMPLETED" or session["current_sequence"] > sequence:
            mark_job(job_id, "COMPLETED")
            return
        evaluation = (
            loads(turn["evaluation"])
            if turn["evaluation"]
            else ai.evaluate(
                turn["kind"], turn["prompt"], turn["reference_answer"], turn["answer"]
            )
        )
        with transaction() as connection:
            owned_activity(connection, user_id, activity_id, True)
            connection.execute(
                "UPDATE interview_turn SET evaluation=%s WHERE id=%s",
                (dumps(evaluation), turn["id"]),
            )
        # Commit grading before generating a follow-up; generation failure must not
        # discard valid assessment evidence or keep a DB lock during model inference.
        generated = None
        if (
            activity["state"] != "FINISHING"
            and evaluation["scorable"]
            and turn["followup_depth"] < 2
        ):
            try:
                generated = ai.follow_up(
                    turn["kind"],
                    turn["topic"],
                    turn["prompt"],
                    turn["answer"],
                    turn["reference_answer"],
                    turn["followup_depth"] + 1,
                )
            except Exception:
                pass  # Fall back to the next published seed.
        with transaction() as connection:
            activity = owned_activity(connection, user_id, activity_id, True)
            session = one(
                connection,
                "SELECT * FROM interview_session WHERE activity_id=%s",
                (activity_id,),
            )
            if (
                activity["state"] == "COMPLETED"
                or session["current_sequence"] > sequence
            ):
                pass
            elif (
                activity["state"] == "FINISHING"
                or sequence + 1 >= session["max_questions"]
                or now() >= session["wall_deadline"]
            ):
                build_interview_report(connection, activity)
            else:
                depth = turn["followup_depth"]
                next_turn = None
                if generated:
                    next_turn = (
                        turn["topic"],
                        turn["kind"],
                        generated,
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
        "rubricVersion": ai.RUBRIC_VERSION,
        "scoringVersion": ai.SCORING_VERSION,
        "scorableAnswers": len(scores),
        "totalAnswers": len(turns),
    }
    competencies = {
        topic: sum(values) / len(values) for topic, values in by_topic.items()
    }
    complete_activity(connection, activity, report, score, competencies)


@app.post("/api/v1/interviews/{activity_id}/finish")
def finish_interview(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        if activity["module"] != "INTERVIEW":
            raise ApiError(404, "NOT_FOUND", "The interview was not found.")
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
            if job["state"] == "FAILED":
                if job["attempts"] >= 5:
                    raise ApiError(
                        409,
                        "RETRY_LIMIT",
                        "The retry limit was reached. Contact the administrator.",
                    )
                connection.execute(
                    "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s",
                    (job["id"],),
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


# Shared scoring status and retry API.
def scoring_view(connection, activity: dict) -> dict:
    report = loads(activity["report"])
    job = one(
        connection,
        "SELECT * FROM job WHERE activity_id=%s AND user_id=%s "
        "AND kind IN ('GRADE_TEST','INTERVIEW_ANSWER','ENGLISH_REPORT') ORDER BY created_at DESC LIMIT 1",
        (activity["id"], activity["user_id"]),
        required=False,
    )
    state = "NOT_REQUESTED"
    if activity["state"] == "COMPLETED" and report and not report.get("pending"):
        state = "COMPLETED"
    elif job and job["state"] in ("QUEUED", "RUNNING", "FAILED"):
        state = job["state"]
    elif activity["state"] == "COMPLETED":
        state = "NEEDS_REVIEW" if report and report.get("pending") else "COMPLETED"
    return {
        "activityId": activity["id"],
        "module": activity["module"],
        "state": state,
        "jobId": job["id"] if job else None,
        "score": activity["score"] if state in ("COMPLETED", "NEEDS_REVIEW") else None,
        "eligible": activity["eligible"] if state == "COMPLETED" else False,
        "provisional": bool(report and report.get("provisional")),
        "retryable": bool(state == "FAILED" and job and job["attempts"] < 5),
        "errorCode": job["error_code"] if job and state == "FAILED" else None,
        "report": report,
    }


@app.get(
    "/api/v1/activities/{activity_id}/scoring",
    response_model=ScoringStatus,
    tags=["Scoring"],
)
def get_scoring(activity_id: str, user_id: User):
    """Read scoring status, validated feedback and eligibility for an owned activity."""
    with transaction() as connection:
        return scoring_view(
            connection, owned_activity(connection, user_id, activity_id)
        )


@app.post(
    "/api/v1/activities/{activity_id}/scoring",
    response_model=ScoringStatus,
    tags=["Scoring"],
    responses={
        202: {
            "model": ScoringStatus,
            "description": "Scoring accepted; poll GET at the same URL.",
        }
    },
)
def request_scoring(
    activity_id: str, body: ScoringRequest, response: Response, user_id: User
):
    """Finalize saved answers and queue scoring. Send {}. Repeats reuse the same job/result.

    Questions, reference answers and rubric weights are read exclusively from server records.
    Failed scoring may be retried up to five processing attempts. A test's objective
    answers use its verified key; free-text answers use local AI and remain provisional.
    """
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        current = scoring_view(connection, activity)
        if activity["state"] == "COMPLETED" and current["state"] != "FAILED":
            return current
        payload = {"activityId": activity_id}
        if activity["module"] == "TEST":
            kind, state = "GRADE_TEST", "PROCESSING"
        elif activity["module"] == "ENGLISH":
            require_consent(connection, user_id)
            attempt = one(
                connection,
                "SELECT answer FROM english_attempt WHERE activity_id=%s",
                (activity_id,),
            )
            if not attempt["answer"]:
                raise ApiError(
                    409,
                    "ANSWER_REQUIRED",
                    "Submit your written answer first.",
                )
            kind, state = "ENGLISH_REPORT", "PROCESSING"
        else:
            require_consent(connection, user_id)
            answers = all_rows(
                connection,
                "SELECT sequence,evaluation FROM interview_turn WHERE activity_id=%s AND answer IS NOT NULL ORDER BY sequence",
                (activity_id,),
            )
            if not answers:
                raise ApiError(
                    409, "ANSWER_REQUIRED", "Submit at least one answer before scoring."
                )
            pending = next((turn for turn in answers if not turn["evaluation"]), None)
            if pending is None:
                build_interview_report(connection, activity)
                return scoring_view(
                    connection, owned_activity(connection, user_id, activity_id)
                )
            payload["sequence"] = pending["sequence"]
            kind, state = "INTERVIEW_ANSWER", "FINISHING"
        job = enqueue_job(connection, user_id, kind, activity_id, payload)
        if job["state"] == "FAILED":
            if job["attempts"] >= 5:
                raise ApiError(
                    409,
                    "RETRY_LIMIT",
                    "The retry limit was reached. Contact the administrator.",
                )
            connection.execute(
                "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s",
                (job["id"],),
            )
        connection.execute(
            "UPDATE activity SET state=%s WHERE id=%s", (state, activity_id)
        )
        result = scoring_view(
            connection, owned_activity(connection, user_id, activity_id)
        )
    response.status_code = 202
    _worker_wake.set()
    return result


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
        if job["kind"] not in _worker_kinds:
            raise ApiError(
                409, "FEATURE_REMOVED", "This job type is no longer supported."
            )
        if job["state"] != "FAILED":
            return job_view(job)
        if job["attempts"] >= 5:
            raise ApiError(
                409,
                "RETRY_LIMIT",
                "The retry limit was reached. Contact the administrator.",
            )
        connection.execute(
            "UPDATE job SET state='QUEUED',error_code=NULL WHERE id=%s", (job_id,)
        )
        _worker_wake.set()
        return {**job_view(job), "state": "QUEUED", "errorCode": None}


# Written English self-introduction.
@app.post("/api/v1/english/attempts")
def create_english(
    body: dict,
    user_id: User,
    idempotency_key: Annotated[str, Header(alias="Idempotency-Key")],
):
    previous = body.get("previousId") or ""
    with transaction() as connection:
        require_consent(connection, user_id)
        existing = one(
            connection,
            "SELECT * FROM activity WHERE user_id=%s AND submission_key=%s",
            (user_id, idempotency_key),
            required=False,
        )
        if existing:
            context = loads(existing["context"], {})
            if (
                existing["module"] != "ENGLISH"
                or context.get("previousId") != previous
                or context.get("promptVersion") != "intro-written-v1"
            ):
                raise ApiError(
                    409, "IDEMPOTENCY_CONFLICT", "This request key was used before."
                )
            return {"activity": activity_view(existing)}
        group = str(uuid.uuid4())
        if previous:
            prior = owned_activity(connection, user_id, previous)
            if prior["module"] != "ENGLISH":
                raise ApiError(404, "NOT_FOUND", "The previous attempt was not found.")
            prior_attempt = one(
                connection,
                "SELECT group_id,prompt_version FROM english_attempt WHERE activity_id=%s",
                (previous,),
            )
            if prior_attempt["prompt_version"] != "intro-written-v1":
                raise ApiError(
                    409,
                    "INCOMPATIBLE_ATTEMPT",
                    "Start a new written self-introduction attempt.",
                )
            group = prior_attempt["group_id"]
        context = {
            "previousId": previous,
            "retryGroup": group,
            "promptVersion": "intro-written-v1",
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
                "INSERT INTO english_attempt(activity_id,group_id,previous_id,prompt_version) VALUES(%s,%s,%s,%s)",
                (activity["id"], group, previous or None, "intro-written-v1"),
            )
        return {"activity": activity_view(activity)}


@app.post("/api/v1/english/attempts/{activity_id}/submit")
def submit_english(activity_id: str, body: dict, user_id: User):
    answer = body.get("text")
    if (
        set(body) != {"text"}
        or not isinstance(answer, str)
        or not 1 <= len(answer.strip()) <= 8000
    ):
        raise ApiError(
            422, "INVALID_INPUT", "Enter a written introduction before submitting."
        )
    answer = answer.strip()
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id, True)
        if activity["module"] != "ENGLISH":
            raise ApiError(404, "NOT_FOUND", "The English attempt was not found.")
        attempt = one(
            connection,
            "SELECT * FROM english_attempt WHERE activity_id=%s",
            (activity_id,),
        )
        if activity["state"] != "ACTIVE":
            if attempt["answer"] != answer:
                raise ApiError(
                    409,
                    "ANSWER_EXISTS",
                    "This attempt already has an accepted answer. Start a retry attempt.",
                )
            job = enqueue_job(
                connection,
                user_id,
                "ENGLISH_REPORT",
                activity_id,
                {"activityId": activity_id},
            )
            return {"activityId": activity_id, "jobId": job["id"]}
        connection.execute(
            "UPDATE english_attempt SET answer=%s WHERE activity_id=%s",
            (answer, activity_id),
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
                "SELECT * FROM english_attempt WHERE activity_id=%s",
                (activity_id,),
            )
        prompt = "Introduce yourself, describe one project and your contribution, and explain your career goal."
        evaluation = ai.evaluate("ENGLISH", prompt, prompt, attempt["answer"])
        result_score = (
            ai.score("ENGLISH", evaluation["dimensions"])
            if evaluation["scorable"]
            else None
        )
        report = {
            "module": "ENGLISH",
            "score": result_score,
            "evaluation": evaluation,
            "answer": attempt["answer"],
            "promptVersion": attempt["prompt_version"],
            "rubricVersion": evaluation["rubricVersion"],
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
                    "english-" + key: float(value) * 25
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


# Independent read and account routes keep this module focused on assessment state.
app.include_router(progress.router)


app.include_router(account.router)


app.include_router(admin.router)


# Reviewer finalization stays here because it rebuilds a test report atomically.
@app.post("/api/v1/admin/reviews/{activity_id}/{item_id}")
def admin_review(activity_id: str, item_id: str, body: dict, actor: Admin):
    points, reason = body.get("points"), str(body.get("reason") or "").strip()
    if (
        type(points) not in (int, float)
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
        activity = one(
            connection, "SELECT * FROM activity WHERE id=%s FOR UPDATE", (activity_id,)
        )
        running = one(
            connection,
            "SELECT id FROM job WHERE activity_id=%s AND state IN ('QUEUED','RUNNING')",
            (activity_id,),
            required=False,
        )
        if running:
            raise ApiError(
                409, "STATE_CONFLICT", "Wait for scoring to finish before reviewing."
            )
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
        build_test_report(connection, activity)
    return {"reviewed": True}


admin_directory = config.BACKEND_ROOT / "static" / "admin"
if admin_directory.is_dir():
    app.mount("/admin", StaticFiles(directory=admin_directory, html=True), name="admin")
