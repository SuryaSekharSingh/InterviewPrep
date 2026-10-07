"""InterviewEdge FastAPI application.

Sets up application lifecycle (migrations and background worker), rate limiting,
error handlers, and mounts feature routers.
"""

from __future__ import annotations

import hashlib
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import Annotated

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import (
    account,
    admin,
    config,
    core,
    english,
    interviews,
    jobs,
    progress,
    scoring,
    security,
    tests,
)
from .core import complete_activity, create_activity, owned_activity, require_consent
from .db import all_rows, one, run_migrations, transaction
from .errors import ApiError, install_error_handlers
from .jobs import _worker_kinds, _worker_stop, _worker_wake, job_worker
from .progress import profile_row
from .scoring import ScoringRequest, ScoringStatus, scoring_view
from .shared import dumps, loads

User = Annotated[str, Depends(security.current_user)]


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


# Profile management
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


# Assessment catalogue
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


# Feature routers
app.include_router(security.router)
app.include_router(progress.router)
app.include_router(account.router)
app.include_router(admin.router)
app.include_router(tests.router)
app.include_router(interviews.router)
app.include_router(english.router)
app.include_router(scoring.router)
app.include_router(jobs.router)

admin_directory = config.BACKEND_ROOT / "static" / "admin"
if admin_directory.is_dir():
    app.mount("/admin", StaticFiles(directory=admin_directory, html=True), name="admin")
