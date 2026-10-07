"""Background job queue: enqueueing, dispatch, worker loop and endpoints."""

from __future__ import annotations

import threading
import uuid
from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, Depends

from . import security
from .db import all_rows, one, transaction
from .errors import ApiError
from .shared import dumps, loads, now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()

_worker_stop = threading.Event()
_worker_wake = threading.Event()
_worker_kinds = ("GRADE_TEST", "INTERVIEW_ANSWER", "ENGLISH_REPORT")


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


def dispatch_job(job: dict):
    # Lazy imports avoid circular dependencies: each feature module imports
    # enqueue_job/mark_job from this module, and this module imports their
    # processors only at dispatch time.
    payload = loads(job["payload"], {})
    if job["kind"] == "GRADE_TEST":
        from .tests import process_test

        process_test(job["user_id"], payload["activityId"], job["id"])
    elif job["kind"] == "INTERVIEW_ANSWER":
        from .interviews import process_interview_answer

        process_interview_answer(
            job["user_id"], payload["activityId"], payload["sequence"], job["id"]
        )
    elif job["kind"] == "ENGLISH_REPORT":
        from .english import process_english

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


# Job query and retry endpoints.


@router.get("/api/v1/jobs")
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


@router.get("/api/v1/jobs/{job_id}")
def get_job(job_id: str, user_id: User):
    with transaction() as connection:
        return job_view(
            one(
                connection,
                "SELECT * FROM job WHERE id=%s AND user_id=%s",
                (job_id, user_id),
            )
        )


@router.post("/api/v1/jobs/{job_id}/retry")
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
