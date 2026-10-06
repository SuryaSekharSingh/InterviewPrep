# InterviewEdge

InterviewEdge is a native Android placement-practice app backed by a local Python API. The current implementation includes local accounts, subject tests, interview sessions, English practice, reports, progress tracking, recommendations and a small content administration page.

The project is still under development. The Android UI redesign is paused while backend functionality is completed.

## Project structure

- `android/` — Java/XML Android application.
- `backend-python/` — primary FastAPI backend, database migrations, starter content and admin assets.
- `backend-java-legacy/` — archived Spring Boot implementation retained for reference; it is not used at runtime.
- `scripts/` — setup, startup and emulator-connection helpers.
- `docs/` — setup, architecture decisions, benchmarks and implementation status.

## What runs where

- Android Studio runs the app in an emulator.
- FastAPI, PostgreSQL and Ollama run on the laptop.
- The emulator reaches the laptop backend through Android Debug Bridge port forwarding.
- PostgreSQL stores accounts and application data. Room/SQLite stores only Android cache and drafts.

Firebase is not required. Accounts use local password login and one-time recovery codes.

AI scoring is available through `POST` and `GET /api/v1/activities/{activityId}/scoring`. See [the scoring API guide](docs/SCORING-API.md) for submission, polling, retries, rubric weights and tests.

## First-time setup

Install Python 3.11 or 3.12, JDK 17, Android Studio with Android SDK 35, and PostgreSQL. This workspace already contains a configured local PostgreSQL installation; a fresh clone needs the database setup described in [docs/SETUP.md](docs/SETUP.md).

From PowerShell in the repository root, create the isolated Python environment:

```powershell
.\scripts\setup-python-backend.ps1
```

Android dependencies are downloaded by Android Studio during Gradle sync. The app uses Java 17 and Android SDK 35.

## Run the backend

Open PowerShell in the repository root:

```powershell
.\scripts\start-local-ai.ps1
.\scripts\start-local.ps1 -UseWorkspaceDatabase
```

Keep this terminal open. Wait for:

```text
Uvicorn running on http://127.0.0.1:8080
```

After backend code changes, stop the old backend with **Ctrl+C** and run the start command again. Running the Android app again does not restart the Python backend. The startup script now stops with a clear message if an older server already occupies port 8080.

Then verify these pages on the laptop:

- Health: <http://127.0.0.1:8080/health>
- Interactive API documentation: <http://127.0.0.1:8080/docs>
- Content administration: <http://127.0.0.1:8080/admin/>

The health response should contain `"backend":"FastAPI"` and `"status":"UP"`.

Ollama is needed for interview, written English and short-answer evaluation. Login, profiles and objective tests continue to work without it.

## Run the Android app in the emulator

1. In Android Studio, choose **File → Open** and select the `android` folder inside this repository.
2. Wait for Gradle sync to complete.
3. Open **View → Tool Windows → Device Manager** and start one emulator.
4. Wait until the emulator home screen appears.
5. Keep the backend terminal running.
6. Open a second PowerShell terminal in the repository root and run:

   ```powershell
   .\scripts\connect-emulator.ps1
   ```

7. In Android Studio's top toolbar, select the **app** run configuration and the running emulator.
8. Click **Run ▶** or choose **Run → Run 'app'**.

Run `connect-emulator.ps1` again whenever the emulator is restarted. It forwards the emulator's `127.0.0.1:8080` to the same port on the laptop.

## Daily startup

1. Start the emulator.
2. Run `start-local-ai.ps1` and `start-local.ps1 -UseWorkspaceDatabase`; keep that terminal open.
3. Run `connect-emulator.ps1` in a second terminal.
4. Press **Run ▶** in Android Studio.

## Complete a mock interview

From **Practice → Mock interview**, choose a role, skills, style and duration. Type your answer and tap **Submit answer and continue**. The app saves it, pauses answer time while the laptop evaluates it, and then shows the next question. Use **Check for next question** if the processing screen is still visible. **Finish interview** ends the session and opens its report.

The backend and Ollama must be running for feedback and follow-up questions. A failed processing job appears with a retry action. **Practice → Written self-introduction** accepts typed English, provides grammar/clarity/relevance feedback and supports retry comparison.

## Verification

Run Python formatting, static checks and API integration tests:

```powershell
$env:PYTHONPATH = (Join-Path (Get-Location) 'backend-python')
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/interviewedge_test'
$env:DB_USER = 'interviewedge'
$env:DB_PASSWORD = (Get-Content .local/postgres-app-password.txt -Raw).Trim()
.\.tools\python-backend\Scripts\python.exe -m ruff format --check backend-python
.\.tools\python-backend\Scripts\python.exe -m ruff check backend-python
.\.tools\python-backend\Scripts\python.exe -m pytest backend-python/tests -q
```

Build and lint Android:

```powershell
.\android\gradlew.bat -p android assembleDebug lintDebug --no-daemon --max-workers=1
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Troubleshooting

| Problem | Resolution |
|---|---|
| `Failed to connect to /127.0.0.1:8080` | Open the health page on the laptop. If it fails, start the backend. If it works, start one emulator and rerun `connect-emulator.ps1`. |
| Android Studio shows `Add Configuration…` | Open the `android` folder itself, wait for Gradle sync, then choose the `app` configuration. |
| Gradle download times out | Follow the local Gradle instructions in [docs/SETUP.md](docs/SETUP.md). |
| Port 8080 is already in use | Check `/health`. Reuse it if it is InterviewEdge FastAPI; otherwise stop the conflicting program before starting this backend. |
| AI report fails | Confirm Ollama is running and `qwen3:4b` is installed. The accepted answer remains saved and its job can be retried. |
| Tests or interviews show no content | The included starter bank has 45 objective questions and 12 interview prompts. A complete independently reviewed faculty bank is still pending. |

## Stop the system

Press **Ctrl+C** in the backend terminal, then stop the emulator. To stop the workspace database too:

```powershell
.\scripts\stop-workspace-database.ps1
```

The default services bind to loopback for local development. Use HTTPS and a deployment-specific configuration before exposing the backend to a network.
