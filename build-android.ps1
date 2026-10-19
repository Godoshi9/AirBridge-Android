param([switch]$Debug)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$engineRoot = Join-Path $projectRoot 'native\airbridge-engine'
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$ndkRoot = Join-Path $sdkRoot 'ndk\30.0.16248370'
if (!(Test-Path $ndkRoot)) { throw "Android NDK r30 was not found at $ndkRoot" }
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_NDK_HOME = $ndkRoot
$env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"

Push-Location $engineRoot
try {
    cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o '..\..\app\src\main\jniLibs' build --release --lib
    if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
} finally { Pop-Location }

Push-Location $projectRoot
try {
    if ($Debug) { .\gradlew.bat :app:assembleDebug } else { .\gradlew.bat :app:assembleRelease }
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
