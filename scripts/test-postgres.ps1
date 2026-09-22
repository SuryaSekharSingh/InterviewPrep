$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$passwordFile = Join-Path $projectRoot '.local/postgres-app-password.txt'
if (!(Test-Path $passwordFile)) { throw 'Initialize the workspace database before running PostgreSQL integration tests.' }
$env:EDGE_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/interviewedge_test'
$env:EDGE_TEST_DB_DRIVER = 'org.postgresql.Driver'
$env:EDGE_TEST_DB_USER = 'interviewedge'
$env:EDGE_TEST_DB_PASSWORD = (Get-Content -LiteralPath $passwordFile -Raw).Trim()
& "$PSScriptRoot/maven.ps1" -MavenArgs @('-f', 'backend-java-legacy/pom.xml', 'test')
exit $LASTEXITCODE
