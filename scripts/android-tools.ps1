param([switch]$InstallSdk)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$toolRoot=Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
if(!(Test-Path "$toolRoot/gradle-8.11.1/bin/gradle.bat")) {
    $url='https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    if(!(Test-Path "$toolRoot/gradle.zip")) {
        & curl.exe --silent --show-error --fail --location --retry 5 --retry-all-errors --connect-timeout 30 --output "$toolRoot/gradle.zip" $url
        if($LASTEXITCODE -ne 0){throw 'Gradle download failed'}
    }
    & curl.exe --silent --show-error --fail --location --retry 5 --output "$toolRoot/gradle.sha256" "$url.sha256"
    if($LASTEXITCODE -ne 0){throw 'Gradle checksum download failed'}
    $expected=(Get-Content "$toolRoot/gradle.sha256" -Raw).Trim()
    if((Get-FileHash "$toolRoot/gradle.zip" -Algorithm SHA256).Hash -ne $expected){throw 'Gradle checksum mismatch'}
    Expand-Archive "$toolRoot/gradle.zip" $toolRoot -Force
}
if($InstallSdk -and !(Test-Path "$toolRoot/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat")) {
    & curl.exe --fail --location --retry 5 --retry-all-errors --connect-timeout 30 --output "$toolRoot/android-commandline.zip" 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip'
    if($LASTEXITCODE -ne 0){throw 'Android tools download failed'}
    $sdk=Join-Path $toolRoot 'android-sdk'
    New-Item -ItemType Directory -Path "$sdk/cmdline-tools" -Force | Out-Null
    Expand-Archive "$toolRoot/android-commandline.zip" "$toolRoot/android-unpacked" -Force
    Copy-Item -LiteralPath "$toolRoot/android-unpacked/cmdline-tools" -Destination "$sdk/cmdline-tools/latest" -Recurse -Force
    Write-Output "SDK tools extracted. Review SDK licences using $sdk/cmdline-tools/latest/bin/sdkmanager.bat --licenses before installing SDK packages."
}
