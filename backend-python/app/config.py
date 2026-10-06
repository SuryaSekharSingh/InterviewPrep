from __future__ import annotations

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND_ROOT = ROOT / "backend-python"
DB_URL = os.getenv("DB_URL", "postgresql://interviewedge@127.0.0.1:55432/interviewedge")
DB_USER = os.getenv("DB_USER", "interviewedge")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")
MEDIA_ROOT = Path(os.getenv("MEDIA_ROOT", ROOT / ".runtime" / "media")).resolve()
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://127.0.0.1:11434").rstrip("/")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen3:4b")
ADMIN_UIDS = {
    value.strip() for value in os.getenv("ADMIN_UIDS", "").split(",") if value.strip()
}
