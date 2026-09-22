$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$environment = Join-Path $projectRoot '.tools/python-backend'
$runtime = @(
    (Join-Path $projectRoot '.tools/python-backend/Scripts/python.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs/Python/Python312/python.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs/Python/Python311/python.exe')
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$runtime) {
    $command = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($command -and $command.Source -notlike '*WindowsApps*') { $runtime = $command.Source }
}
if (!$runtime) { throw 'Install Python 3.11 or 3.12, then rerun this script.' }
if (!(Test-Path -LiteralPath (Join-Path $environment 'Scripts/python.exe'))) {
    & $runtime -m venv $environment
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Python environment.' }
}
$python = Join-Path $environment 'Scripts/python.exe'
& $python -m pip install --disable-pip-version-check -r (Join-Path $projectRoot 'backend-python/requirements-dev.txt')
if ($LASTEXITCODE -ne 0) { throw 'Could not install the Python backend dependencies.' }
Write-Output 'Python backend environment is ready.'
