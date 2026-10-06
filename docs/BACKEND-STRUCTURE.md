# Backend simplification review

## Scope and method

The active backend is `backend-python/app`. The archived Java backend is not part of the running application. I mapped every route and helper in the 2,206-line `main.py`, then moved only groups whose dependencies were clear. Each extraction preserved the same paths, HTTP methods, endpoint names, request parameters, SQL statements and response construction. The generated OpenAPI document was compared as parsed JSON before and after each move; all 41 paths and the full specification remained equal.

## Current module boundaries

| Module | Responsibility |
|---|---|
| `main.py` | Assemble FastAPI; own activity finalization, job processing, authentication/profile APIs, tests, interviews, English attempts and scoring status. |
| `progress.py` | Read activities and profiles for dashboard, competency summaries and recommendations. |
| `admin.py` | Check administrator access; validate, create, publish and retire reviewed content; list review work. |
| `account.py` | Export and delete an account, including legacy media retained for existing users. |
| `shared.py` | JSON serialization and UTC clock helpers. |
| `security.py`, `ai.py`, `db.py`, `scoring.py` | Existing identity, AI, persistence and scoring contracts; unchanged in this refactor. |

`main.py` is now 1,614 lines. The remaining length is mainly the test, interview and English workflows. These share transaction-sensitive activity and job transitions, so splitting them further would require a larger design change and more targeted failure/restart tests.

## What was deliberately left in place

- `complete_activity` updates reports, progress evidence and snapshots in one database transaction. Moving it without redesigning the dependency direction could break scoring or regrading.
- The PostgreSQL worker dispatches test, interview and English jobs; changing its lifecycle could lose accepted work during restarts.
- The administrator's final score review remains beside `build_test_report`, which it calls inside the same transaction.
- Existing legacy media remains in account export and deletion so removal of recording features does not strand older users' data.
- Authentication, scoring formulas, content selection, prompts and migrations were not changed for this cleanup.

## Verification

- The OpenAPI specification before and after the extraction is equal as parsed JSON, including all 41 paths.
- Ruff lint and formatting checks pass.
- 59 backend tests pass; seven optional live-model tests are skipped unless explicitly enabled.
- New integration checks cover account export/deletion isolation and administrator route access after the route moves.

Restart the running FastAPI process after changing Python files; the backend does not reload automatically in the documented startup mode.
