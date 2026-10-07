"""Written English self-introduction routes and AI evaluation."""

from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Header

from . import ai, security
from .core import (
    complete_activity,
    create_activity,
    owned_activity,
    require_consent,
)
from .db import all_rows, one, transaction
from .errors import ApiError
from .jobs import enqueue_job, mark_job
from .progress import activity_view
from .shared import loads, now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


@router.post("/api/v1/english/attempts")
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


@router.post("/api/v1/english/attempts/{activity_id}/submit")
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


@router.get("/api/v1/english/attempts/{activity_id}/report")
def english_report(activity_id: str, user_id: User):
    with transaction() as connection:
        activity = owned_activity(connection, user_id, activity_id)
        return (
            loads(activity["report"])
            if activity["report"]
            else {"state": activity["state"]}
        )
