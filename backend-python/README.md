# InterviewEdge FastAPI backend

This is the primary InterviewEdge backend.

## Components

- `app/main.py` — application assembly, test/interview/English state transitions, scoring and the durable single-job worker.
- `app/progress.py` — dashboard, competency and recommendation read models and routes.
- `app/admin.py` — reviewed-question validation and content administration routes. Reviewer score finalization remains in `main.py` with the test report transaction.
- `app/account.py` — account export and deletion, including retained legacy media cleanup.
- `app/shared.py` — small JSON and UTC-time helpers used across feature modules.
- `app/security.py` — password hashing, opaque sessions and recovery codes.
- `app/ai.py` — local Ollama evaluation and follow-up adapter.
- `app/scoring.py` — typed scoring request/status contracts; see [the API guide](../docs/SCORING-API.md).
- `app/db.py` — PostgreSQL access and migration runner.
- `migrations/` — schema, catalogue and starter-content migrations.
- `static/admin/` — local content administration interface.
- `tests/` — PostgreSQL integration tests.

Run `scripts/setup-python-backend.ps1` once, then `scripts/start-local.ps1 -UseWorkspaceDatabase` from the repository root. Interactive API documentation is served at <http://127.0.0.1:8080/docs>.

The API remains compatible with the existing Java Android client. The archived Spring implementation is not loaded or required by this backend.
