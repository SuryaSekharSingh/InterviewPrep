"""Subject test routes: creation, answer saving, submission, grading and reports."""

from __future__ import annotations

import random
import uuid
from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, Header

from . import ai, security
from .core import complete_activity, create_activity, owned_activity
from .db import all_rows, one, transaction
from .errors import ApiError
from .jobs import enqueue_job, mark_job
from .progress import activity_view
from .shared import dumps, loads, now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


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


@router.post("/api/v1/tests/attempts")
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


@router.get("/api/v1/tests/attempts/{activity_id}")
def get_test(activity_id: str, user_id: User):
    with transaction() as connection:
        return test_view(connection, user_id, activity_id)


@router.put("/api/v1/tests/attempts/{activity_id}/responses/{item_id}")
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


@router.post("/api/v1/tests/attempts/{activity_id}/submit")
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


@router.get("/api/v1/tests/attempts/{activity_id}/result")
def test_result(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )
