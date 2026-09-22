# InterviewEdge FastAPI backend

This is the primary InterviewEdge backend.

## Components

- `app/main.py` — versioned HTTP API, domain workflows and the durable single-job worker.
- `app/security.py` — password hashing, opaque sessions and recovery codes.
- `app/ai.py` — replaceable Ollama and whisper.cpp adapters.
- `app/db.py` — PostgreSQL access and migration runner.
- `migrations/` — schema, catalogue and starter-content migrations.
- `static/admin/` — local content administration interface.
- `tests/` — PostgreSQL integration tests.

Run `scripts/setup-python-backend.ps1` once, then `scripts/start-local.ps1 -UseWorkspaceDatabase` from the repository root. Interactive API documentation is served at <http://127.0.0.1:8080/docs>.

The API remains compatible with the existing Java Android client. The archived Spring implementation is not loaded or required by this backend.
