param([Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$toolRoot = Join-Path $projectRoot '.tools'
$mavenHome = Join-Path $toolRoot 'apache-maven-3.9.11'
if (!(Test-Path "$mavenHome/bin/mvn.cmd")) {
    New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
    $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip'
    Invoke-WebRequest $url -OutFile "$toolRoot/maven.zip"
    $expected = ((Invoke-WebRequest "$url.sha512").Content.Trim() -split '\s+')[0]
    if ((Get-FileHash "$toolRoot/maven.zip" -Algorithm SHA512).Hash -ne $expected) { throw 'Maven checksum mismatch' }
    Expand-Archive -LiteralPath "$toolRoot/maven.zip" -DestinationPath $toolRoot -Force
}
& "$mavenHome/bin/mvn.cmd" -s "$PSScriptRoot/maven-settings.xml" '-Dmaven.wagon.http.retryHandler.count=5' "-Dmaven.repo.local=$toolRoot/m2" @MavenArgs
exit $LASTEXITCODE
