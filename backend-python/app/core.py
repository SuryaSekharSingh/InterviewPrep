"""Shared activity lifecycle and consent helpers used across feature modules."""

from __future__ import annotations

import hashlib
import uuid

from .db import all_rows, one
from .errors import ApiError
from .progress import progress_summary
from .shared import dumps, loads, now


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
