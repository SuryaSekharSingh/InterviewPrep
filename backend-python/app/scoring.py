"""Public scoring contracts and assessment finalization endpoints."""

from __future__ import annotations

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict, Field

from . import security
from .core import owned_activity, require_consent
from .db import all_rows, one, transaction
from .errors import ApiError
from .jobs import _worker_wake, enqueue_job
from .shared import loads

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


class ScoringRequest(BaseModel):
    # Answers and rubrics belong to persisted activities, never this request.
    model_config = ConfigDict(extra="forbid")


class ScoringStatus(BaseModel):
    activityId: str
    module: Literal["TEST", "INTERVIEW", "ENGLISH"]
    state: Literal[
        "NOT_REQUESTED", "QUEUED", "RUNNING", "FAILED", "COMPLETED", "NEEDS_REVIEW"
    ]
    jobId: str | None = None
    score: float | None = Field(default=None, ge=0, le=100)
    eligible: bool
    provisional: bool = False
    retryable: bool = False
    errorCode: str | None = None
    report: dict | None = None


def scoring_view(connection, activity: dict) -> dict:
    report = loads(activity.get("report")) if activity.get("report") else None
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


@router.get(
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


@router.post(
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
    from .interviews import build_interview_report

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

