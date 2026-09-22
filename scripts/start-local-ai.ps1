param([string]$Model = 'qwen3:4b')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$executable = Join-Path $projectRoot '.tools/ollama/ollama.exe'
if (!(Test-Path -LiteralPath $executable)) {
    throw 'Install Ollama into .tools/ollama first. See docs/SETUP.md.'
}
$endpoint = 'http://127.0.0.1:11434'
function Read-Models {
    try { return Invoke-RestMethod -Uri "$endpoint/api/tags" -TimeoutSec 2 }
    catch { return $null }
}
$models = Read-Models
if ($null -eq $models) {
    $localDirectory = Join-Path $projectRoot '.local'
    New-Item -ItemType Directory -Path $localDirectory -Force | Out-Null
    $env:OLLAMA_HOST = '127.0.0.1:11434'
    $env:OLLAMA_MODELS = Join-Path $projectRoot '.runtime/ollama-models'
    $env:OLLAMA_NUM_PARALLEL = '1'
    $env:OLLAMA_MAX_LOADED_MODELS = '1'
    $env:OLLAMA_NO_CLOUD = '1'
    $env:OLLAMA_DEBUG = '0'
    $process = Start-Process -FilePath $executable -ArgumentList 'serve' -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $localDirectory 'ollama-stdout.log') `
        -RedirectStandardError (Join-Path $localDirectory 'ollama-stderr.log')
    $process.Id | Set-Content -LiteralPath (Join-Path $localDirectory 'ollama.pid')
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $models = Read-Models
        if ($null -ne $models) { break }
        $process.Refresh()
        if ($process.HasExited) { throw 'Ollama exited during startup. Check .local/ollama-stderr.log.' }
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $models) { throw 'Ollama did not become ready. Check .local/ollama-stderr.log.' }
}
if ($Model -notin @($models.models | ForEach-Object { $_.name })) {
    throw "Ollama is reachable, but model '$Model' is not installed. Follow docs/SETUP.md to download it."
}
Write-Output "Local AI is reachable at $endpoint with $Model installed."
Write-Output 'If Ollama was already running, this script reused its existing configuration.'
