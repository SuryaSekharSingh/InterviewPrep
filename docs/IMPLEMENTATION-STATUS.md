# Implementation checkpoint

Updated 2026-10-07. Audio recording and transcription have been removed from active features. The backend route/read-model cleanup is described in [BACKEND-STRUCTURE.md](BACKEND-STRUCTURE.md). The full faculty-review release is still in progress.

## Current decisions

- FastAPI/Python is the primary backend.
- PostgreSQL remains the authoritative store for accounts, content and assessments.
- Android Room/SQLite remains a cache and draft store.
- Firebase and Google sign-in are not used.
- The Android Studio emulator is the main device target.
- The UI redesign is paused while backend functionality is completed.

## Implemented

- Local username/password registration, login, logout, password changes and rotating recovery codes.
- Profile and consent settings.
- DSA, DBMS and OS tests with objective scoring and provisional AI short-answer review.
- Java Developer, Backend Developer and Software Engineer interview setup, persisted turns, AI evaluation, follow-ups and reports.
- Text-only interview answers and follow-ups.
- Written English self-introduction evaluation and retry grouping.
- Shared competency evidence, module scores, progress snapshots and rule-based recommendations.
- PostgreSQL-backed jobs with restart recovery and one-at-a-time local AI processing.
- Account export and deletion.
- Admin content APIs and the local `/admin/` interface.
- FastAPI interactive documentation at `/docs` and generated OpenAPI JSON.
- Seven independent Python-owned SQL migrations, including 45 starter objective questions and 12 starter interview prompts.

Previously stored recordings and media metadata are retained only for legacy account export and deletion. No new media can be uploaded or transcribed through the API.

The archived `backend-java-legacy` directory is kept only for historical reference. FastAPI does not load code, migrations or static files from it.

## Verified

- Scoring work on 2026-10-06: 52 automated checks and seven opt-in live Ollama checks passed. Coverage includes weighted totals, strict AI response validation, authentication/ownership, duplicate requests during inference, retry limits, review concurrency, invalidated evidence, immutable English submissions and real-model scoring through the new endpoint. See [SCORING-API.md](SCORING-API.md).
- Real-model synthetic comparison: correct answer 85, partial answer 67.5, incorrect answer 0. These are individual checks, not validated assessment accuracy. See [BENCHMARKS.md](BENCHMARKS.md).

- Python formatting and Ruff static checks pass. The current text-only backend suite passed 59 tests, with seven optional live-model checks skipped.
- FastAPI/PostgreSQL integration tests pass for account recovery, ownership, all three subjects at all difficulties, test submission/report generation, progress snapshots and all role/interview-type combinations.
- Live HTTP smoke testing has covered health, registration, profile consent, catalogue, a five-question test, interview creation and dashboard output.
- The main database contains 45 published starter questions and 12 published starter interview prompts.
- Android `assembleDebug` and `lintDebug` pass after removing recording and transcription controls.
- Local Ollama feasibility runs succeeded. Earlier whisper.cpp measurements in [BENCHMARKS.md](BENCHMARKS.md) are historical and do not describe the active product.

## Remaining release work

1. Expand and independently review the content bank to the agreed release target. Starter content is usable but is not the final faculty-reviewed bank.
2. Evaluate at least 60 reviewer-scored AI examples.
3. Complete emulator journeys, rotation/process restoration, 320 dp layout and enlarged-text checks.
4. Complete the paused UI redesign and accessibility review.
5. Verify backup/restore and account export/delete on the final packaged build.
6. Finish interview difficulty behavior and score-version compatibility checks.
7. Run the complete faculty walkthrough after restarting every local service.

## Safe resume procedure

Read this file and [SETUP.md](SETUP.md), inspect the working tree, and check ports 8080, 11434 and 55432 before starting another process. Preserve unrelated IDE and root starter files. Run the affected Python tests after backend changes and Android build/lint after client changes.
