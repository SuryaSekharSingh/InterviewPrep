from __future__ import annotations

import hashlib
import re
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from typing import Annotated

import bcrypt
from fastapi import APIRouter, Depends, Header
from psycopg.errors import UniqueViolation

from .db import one, transaction
from .errors import ApiError

USERNAME = re.compile(r"^[A-Za-z0-9_.-]{3,40}$")
SESSION_DAYS = 7


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _password(value: str) -> bytes:
    raw = value.encode("utf-8")
    if len(value) < 10 or len(raw) > 72:
        raise ApiError(422, "INVALID_INPUT", "Use a password of 10 to 72 UTF-8 bytes.")
    return raw


def _codes(connection, user_id: str) -> list[str]:
    codes = [secrets.token_urlsafe(18) for _ in range(8)]
    connection.execute("DELETE FROM recovery_code WHERE user_id=%s", (user_id,))
    with connection.cursor() as cursor:
        cursor.executemany(
            "INSERT INTO recovery_code(code_hash,user_id) VALUES(%s,%s)",
            [(_hash(code), user_id) for code in codes],
        )
    return codes


def _session(connection, user_id: str, recovery_codes=None):
    token = secrets.token_urlsafe(32)
    now = datetime.now(timezone.utc)
    expires = now + timedelta(days=SESSION_DAYS)
    connection.execute(
        "INSERT INTO login_session(token_hash,user_id,authenticated_at,expires_at) VALUES(%s,%s,%s,%s)",
        (_hash(token), user_id, now, expires),
    )
    return {
        "userId": user_id,
        "token": token,
        "expiresAt": expires,
        "recoveryCodes": recovery_codes or [],
    }


def register(username: str, password: str):
    username = (username or "").strip().lower()
    if not USERNAME.fullmatch(username):
        raise ApiError(
            422,
            "INVALID_INPUT",
            "Username must use 3–40 letters, numbers, dots, dashes or underscores.",
        )
    password_hash = bcrypt.hashpw(
        _password(password), bcrypt.gensalt(rounds=12)
    ).decode()
    user_id = str(uuid.uuid4())
    try:
        with transaction() as connection:
            connection.execute("INSERT INTO edge_user(id) VALUES(%s)", (user_id,))
            connection.execute("INSERT INTO profile(user_id) VALUES(%s)", (user_id,))
            connection.execute(
                "INSERT INTO local_account(user_id,username,password_hash) VALUES(%s,%s,%s)",
                (user_id, username, password_hash),
            )
            return _session(connection, user_id, _codes(connection, user_id))
    except UniqueViolation as error:
        raise ApiError(
            409, "USERNAME_EXISTS", "That username is already registered."
        ) from error


def login(username: str, password: str):
    with transaction() as connection:
        account = one(
            connection,
            "SELECT a.user_id,a.password_hash,u.status FROM local_account a JOIN edge_user u ON u.id=a.user_id "
            "WHERE a.username=%s FOR UPDATE",
            ((username or "").strip().lower(),),
            required=False,
        )
        if (
            not account
            or account["status"] != "ACTIVE"
            or not bcrypt.checkpw(
                _password(password), account["password_hash"].encode()
            )
        ):
            raise ApiError(
                401, "INVALID_CREDENTIALS", "Username or password is incorrect."
            )
        return _session(connection, account["user_id"])


def recover(username: str, recovery_code: str, new_password: str):
    with transaction() as connection:
        account = one(
            connection,
            "SELECT a.user_id FROM local_account a JOIN edge_user u ON u.id=a.user_id "
            "WHERE a.username=%s AND u.status='ACTIVE' FOR UPDATE",
            ((username or "").strip().lower(),),
            required=False,
        )
        if not account:
            raise ApiError(401, "INVALID_RECOVERY", "The recovery details are invalid.")
        used = connection.execute(
            "DELETE FROM recovery_code WHERE user_id=%s AND code_hash=%s",
            (account["user_id"], _hash((recovery_code or "").strip())),
        ).rowcount
        if used != 1:
            raise ApiError(401, "INVALID_RECOVERY", "The recovery details are invalid.")
        connection.execute(
            "UPDATE local_account SET password_hash=%s WHERE user_id=%s",
            (
                bcrypt.hashpw(
                    _password(new_password), bcrypt.gensalt(rounds=12)
                ).decode(),
                account["user_id"],
            ),
        )
        connection.execute(
            "DELETE FROM login_session WHERE user_id=%s", (account["user_id"],)
        )
        return _session(
            connection, account["user_id"], _codes(connection, account["user_id"])
        )


def current_user(authorization: str = Header(default="")) -> str:
    if not authorization.startswith("Bearer "):
        raise ApiError(401, "UNAUTHENTICATED", "Sign in to continue.")
    token = authorization[7:]
    if len(token) != 43:
        raise ApiError(401, "UNAUTHENTICATED", "Sign in to continue.")
    with transaction() as connection:
        row = one(
            connection,
            "SELECT s.user_id FROM login_session s JOIN edge_user u ON u.id=s.user_id "
            "WHERE s.token_hash=%s AND s.expires_at>CURRENT_TIMESTAMP AND u.status='ACTIVE'",
            (_hash(token),),
            required=False,
        )
    if not row:
        raise ApiError(
            401, "UNAUTHENTICATED", "Your session has expired. Sign in again."
        )
    return row["user_id"]


def token_hash(token: str) -> str:
    return _hash(token)


def change_password(user_id: str, current: str, new: str):
    with transaction() as connection:
        account = one(
            connection,
            "SELECT password_hash FROM local_account WHERE user_id=%s FOR UPDATE",
            (user_id,),
        )
        if not bcrypt.checkpw(_password(current), account["password_hash"].encode()):
            raise ApiError(401, "INVALID_CREDENTIALS", "Current password is incorrect.")
        connection.execute(
            "UPDATE local_account SET password_hash=%s WHERE user_id=%s",
            (
                bcrypt.hashpw(_password(new), bcrypt.gensalt(rounds=12)).decode(),
                user_id,
            ),
        )
        connection.execute("DELETE FROM login_session WHERE user_id=%s", (user_id,))
        return _session(connection, user_id, _codes(connection, user_id))


router = APIRouter()


@router.post("/api/v1/auth/register")
def auth_register(body: dict):
    return register(body.get("username"), body.get("password"))


@router.post("/api/v1/auth/login")
def auth_login(body: dict):
    return login(body.get("username"), body.get("password"))


@router.post("/api/v1/auth/recover")
def auth_recover(body: dict):
    return recover(
        body.get("username"), body.get("recoveryCode"), body.get("newPassword")
    )


@router.post("/api/v1/auth/password")
def auth_password(body: dict, user_id: Annotated[str, Depends(current_user)]):
    return change_password(
        user_id, body.get("currentPassword"), body.get("newPassword")
    )


@router.post("/api/v1/auth/reauthenticate")
def auth_reauthenticate(body: dict, user_id: Annotated[str, Depends(current_user)]):
    with transaction() as connection:
        account = one(
            connection,
            "SELECT username FROM local_account WHERE user_id=%s",
            (user_id,),
        )
    return login(account["username"], body.get("password"))


@router.post("/api/v1/auth/logout")
def auth_logout(
    authorization: Annotated[str, Header()], _: Annotated[str, Depends(current_user)]
):
    with transaction() as connection:
        connection.execute(
            "DELETE FROM login_session WHERE token_hash=%s",
            (token_hash(authorization[7:]),),
        )
    return {"signedOut": True}

