"""Small data and time helpers shared by the API's feature modules."""

from __future__ import annotations

import json
from datetime import datetime, timezone


def dumps(value) -> str:
    return json.dumps(value, separators=(",", ":"), default=str)


def loads(value, fallback=None):
    if value is None:
        return fallback
    return json.loads(value) if isinstance(value, str) else value


def now() -> datetime:
    return datetime.now(timezone.utc)
