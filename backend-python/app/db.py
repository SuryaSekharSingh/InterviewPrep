from __future__ import annotations

import re
from contextlib import contextmanager
import psycopg
from psycopg.rows import dict_row

from . import config


def _dsn() -> str:
    url = config.DB_URL
    if url.startswith("jdbc:postgresql://"):
        url = "postgresql://" + url.removeprefix("jdbc:postgresql://")
    return url


def connect(*, autocommit: bool = False):
    return psycopg.connect(
        _dsn(),
        user=config.DB_USER,
        password=config.DB_PASSWORD,
        connect_timeout=5,
        row_factory=dict_row,
        autocommit=autocommit,
    )


@contextmanager
def transaction():
    with connect() as connection:
        with connection.transaction():
            yield connection


def one(connection, sql: str, params=(), *, required: bool = True):
    row = connection.execute(sql, params).fetchone()
    if row is None and required:
        from .errors import ApiError

        raise ApiError(404, "NOT_FOUND", "The requested item was not found.")
    return row


def all_rows(connection, sql: str, params=()):
    return connection.execute(sql, params).fetchall()


def run_migrations() -> None:
    migration_dir = config.BACKEND_ROOT / "migrations"
    files = sorted(
        migration_dir.glob("V*__*.sql"),
        key=lambda path: int(re.match(r"V(\d+)__", path.name).group(1)),
    )
    with connect(autocommit=True) as connection:
        connection.execute(
            "CREATE TABLE IF NOT EXISTS edge_schema_migration("
            "version INTEGER PRIMARY KEY,name TEXT NOT NULL,applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)"
        )
        flyway_exists = one(
            connection,
            "SELECT to_regclass('public.flyway_schema_history') AS table_name",
        )["table_name"]
        if flyway_exists:
            connection.execute(
                "INSERT INTO edge_schema_migration(version,name) "
                "SELECT CAST(version AS INTEGER),description FROM flyway_schema_history "
                "WHERE success AND version ~ '^[0-9]+$' ON CONFLICT(version) DO NOTHING"
            )
        applied = {
            row["version"]
            for row in all_rows(connection, "SELECT version FROM edge_schema_migration")
        }
        for path in files:
            match = re.match(r"V(\d+)__(.+)\.sql", path.name)
            version = int(match.group(1))
            if version in applied:
                continue
            with connection.transaction():
                connection.execute(path.read_text(encoding="utf-8"))
                connection.execute(
                    "INSERT INTO edge_schema_migration(version,name) VALUES(%s,%s)",
                    (version, match.group(2).replace("_", " ")),
                )
