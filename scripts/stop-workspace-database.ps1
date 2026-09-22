$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$pgControl = Join-Path $projectRoot '.tools/postgresql/pgsql/bin/pg_ctl.exe'
$pgData = Join-Path $projectRoot '.runtime/postgres'
if (!(Test-Path $pgControl) -or !(Test-Path "$pgData/PG_VERSION")) { throw 'No workspace database found.' }
Write-Output 'Stop the backend before stopping its database.'
& $pgControl -D $pgData -m fast -w stop
exit $LASTEXITCODE
