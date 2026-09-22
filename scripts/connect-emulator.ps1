param([int]$BackendPort = 8080)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$candidates = @(
    (Join-Path $projectRoot '.tools/android-sdk/platform-tools/adb.exe'),
    (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe')
)
if ($env:ANDROID_HOME) { $candidates = @((Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe')) + $candidates }
$adb = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (!$adb) { throw 'Install Android SDK Platform Tools in Android Studio first.' }
& $adb -e reverse 'tcp:8080' "tcp:$BackendPort"
if ($LASTEXITCODE -ne 0) { throw 'Start one emulator in Android Studio, wait for it to finish booting, and try again.' }
Write-Output 'Emulator connected to the local backend. Run InterviewEdge in Android Studio.'
