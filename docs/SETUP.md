# Local development setup

## Requirements

- Python 3.11 or 3.12.
- JDK 17.
- Android Studio, Android SDK 35 and one Android emulator.
- PostgreSQL 17 bound to loopback.
- Ollama with `qwen3:4b` for AI evaluation.
- whisper.cpp with `ggml-base.en.bin` for recorded answers.

Firebase, Google Services files and paid cloud accounts are not required.

## Python backend environment

From the repository root:

```powershell
.\scripts\setup-python-backend.ps1
```

This creates `.tools/python-backend` and installs the pinned packages from `backend-python/requirements-dev.txt`. The environment and downloaded packages are intentionally excluded from Git.

## PostgreSQL

Create a loopback-only database named `interviewedge` and a non-superuser login named `interviewedge`. Keep its password outside source control.

For a manually installed database, set:

```powershell
$env:DB_URL = 'postgresql://127.0.0.1:5432/interviewedge'
$env:DB_USER = 'interviewedge'
$env:DB_PASSWORD = '<local password>'
$env:MEDIA_ROOT = (Join-Path (Get-Location) '.runtime/media')
.\scripts\start-local.ps1
```

This configured workspace instead uses a private database at port 55432 and stores the password in the ignored `.local` directory:

```powershell
.\scripts\start-local.ps1 -UseWorkspaceDatabase
```

FastAPI applies the versioned SQL files from `backend-python/migrations` at startup. It recognizes migrations previously recorded by Flyway, so existing Spring Boot data remains usable. Never edit an applied migration; add a new numbered migration.

## AI and transcription

On this configured laptop:

```powershell
.\scripts\start-local-ai.ps1
.\scripts\start-local.ps1 -UseWorkspaceDatabase
```

The AI script starts or reuses an Ollama server at `127.0.0.1:11434` and verifies the configured model. It does not download a missing model automatically.

Configuration overrides:

- `OLLAMA_URL` — Ollama address.
- `OLLAMA_MODEL` — text model identifier.
- `WHISPER_EXECUTABLE` — full path to `whisper-cli.exe`.
- `WHISPER_MODEL` — full path to `ggml-base.en.bin`.
- `MEDIA_ROOT` — private recording directory.
- `ADMIN_UIDS` — comma-separated account IDs allowed to use `/api/v1/admin`.

The backend processes one heavy grading or transcription job at a time. Jobs are stored in PostgreSQL and queued/running jobs are recovered when FastAPI restarts.

## Emulator connection

1. Open the repository's `android` folder in Android Studio.
2. Start one emulator from **View → Tool Windows → Device Manager**.
3. Start the backend and keep its terminal open.
4. In another PowerShell terminal run:

   ```powershell
   .\scripts\connect-emulator.ps1
   ```

5. Select **app** and the emulator in Android Studio, then press **Run ▶**.

Repeat the connection script after every emulator restart. The debug application calls `http://127.0.0.1:8080/api/v1/`; Android Debug Bridge forwards that port to the laptop.

## Accounts and administration

Usernames contain 3–40 letters, digits, dots, underscores or hyphens. Passwords must be at least 10 characters and at most 72 UTF-8 bytes.

Registration returns eight recovery codes once. Store them outside the app. Recovery rotates all codes and revokes old sessions. Sessions expire after seven days.

For an administrator account:

1. Register the account normally.
2. Read its user ID from the registration response.
3. Set `ADMIN_UIDS` in the backend terminal.
4. Restart FastAPI.

Do not add an administrator switch to the Android app.

## Gradle on this configured laptop

If Gradle distribution downloads time out, open Android Studio settings at **Build, Execution, Deployment → Build Tools → Gradle** and set:

- Gradle installation: `C:\Users\surya\Desktop\Project Dump\InterviewPrep\.tools\gradle-8.11.1`
- Gradle user home: `C:\Users\surya\Desktop\Project Dump\InterviewPrep\.tools\gradle-home`
- Gradle JDK: `C:\Program Files\Java\jdk-17`

These local paths are not included in a clone.

## Backup and shutdown

Stop accepting work before backup. Back up PostgreSQL with `pg_dump` and copy `.runtime/media` while writes are stopped. Restore and verify the database and media together.

Stop FastAPI with **Ctrl+C**. Then stop the workspace database with `scripts/stop-workspace-database.ps1` if desired. Do not delete database or media folders to recover a failed job.
