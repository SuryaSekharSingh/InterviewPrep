param([switch]$UseWorkspaceDatabase)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if ($UseWorkspaceDatabase) {
    $pgControl = Join-Path $projectRoot '.tools/postgresql/pgsql/bin/pg_ctl.exe'
    $pgReady = Join-Path $projectRoot '.tools/postgresql/pgsql/bin/pg_isready.exe'
    $pgData = Join-Path $projectRoot '.runtime/postgres'
    $passwordFile = Join-Path $projectRoot '.local/postgres-app-password.txt'
    if (!(Test-Path $pgControl) -or !(Test-Path "$pgData/PG_VERSION") -or !(Test-Path $passwordFile)) {
        throw 'The workspace database is not initialized. Follow docs/SETUP.md or configure DB_URL, DB_USER and DB_PASSWORD for your local PostgreSQL.'
    }
    & $pgReady -h 127.0.0.1 -p 55432 -d interviewedge *> $null
    if ($LASTEXITCODE -ne 0) {
        & $pgControl -D $pgData -l (Join-Path $projectRoot '.local/postgres.log') -o '-p 55432 -h 127.0.0.1' -w start
        if ($LASTEXITCODE -ne 0) { throw 'Could not start the workspace database.' }
    }
    $env:DB_URL = 'jdbc:postgresql://127.0.0.1:55432/interviewedge'
    $env:DB_USER = 'interviewedge'
    $env:DB_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
}
if (!$env:DB_PASSWORD) { throw 'Set DB_PASSWORD, or use -UseWorkspaceDatabase after initializing the workspace database.' }
if (!$env:PYTHONPATH) { $env:PYTHONPATH = Join-Path $projectRoot 'backend-python' }
$python = Join-Path $projectRoot '.tools/python-backend/Scripts/python.exe'
if (!(Test-Path -LiteralPath $python)) {
    throw 'The Python backend environment is missing. Run .\scripts\setup-python-backend.ps1 first.'
}
$portCheck = [System.Net.Sockets.TcpClient]::new()
try {
    $portCheck.Connect('127.0.0.1', 8080)
    throw 'Port 8080 already has a running backend. Stop its terminal with Ctrl+C before starting this version.'
} catch [System.Net.Sockets.SocketException] {
    # A refused connection means this backend can bind port 8080.
} finally {
    $portCheck.Dispose()
}
Write-Output 'Starting the InterviewEdge FastAPI backend on http://127.0.0.1:8080.'
Write-Output 'API documentation: http://127.0.0.1:8080/docs  (press Ctrl+C to stop)'
& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8080 --app-dir (Join-Path $projectRoot 'backend-python')
exit $LASTEXITCODE
