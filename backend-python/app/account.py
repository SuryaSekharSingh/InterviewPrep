"""Account export and deletion, including legacy retained media cleanup."""

from __future__ import annotations

import json
import os
import tempfile
import zipfile
from datetime import timedelta
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends
from fastapi.responses import FileResponse

from . import config, security
from .db import all_rows, one, transaction
from .errors import ApiError
from .progress import activity_view, profile_row
from .shared import now

User = Annotated[str, Depends(security.current_user)]
router = APIRouter()


def require_recent_auth(connection, user_id: str):
    recent = one(
        connection,
        "SELECT 1 ok FROM login_session WHERE user_id=%s AND authenticated_at>=%s LIMIT 1",
        (user_id, now() - timedelta(minutes=5)),
        required=False,
    )
    if not recent:
        raise ApiError(401, "REAUTHENTICATION_REQUIRED", "Confirm your password again.")


@router.post("/api/v1/me/exports")
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


@router.delete("/api/v1/me")
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
