$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location (Join-Path $root "server")
try {
  pnpm typecheck
  pnpm test
  pnpm build
} finally {
  Pop-Location
}

Write-Host "Server checks passed."

$androidHome = $env:ANDROID_HOME
if (-not $androidHome) {
  $androidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

if (Test-Path (Join-Path $root "android-app\gradlew.bat")) {
  $previousJavaHome = $env:JAVA_HOME
  $previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
  $previousAndroidHome = $env:ANDROID_HOME
  $previousAndroidSdkRoot = $env:ANDROID_SDK_ROOT
  try {
    if (-not $env:JAVA_HOME) {
      $env:JAVA_HOME = "C:\tools\jdk-17.0.21.9"
    }
    $env:ANDROID_HOME = $androidHome
    $env:ANDROID_SDK_ROOT = $androidHome
    if ($env:OS -eq "Windows_NT" -and -not $env:JAVA_TOOL_OPTIONS) {
      $env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=Z:\__xinyu_no_such_dir__"
    }
    Push-Location (Join-Path $root "android-app")
    try {
      .\gradlew.bat --no-daemon --no-watch-fs --console=plain testDebugUnitTest assembleDebug lintDebug
    } finally {
      Pop-Location
    }
  } finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    $env:ANDROID_HOME = $previousAndroidHome
    $env:ANDROID_SDK_ROOT = $previousAndroidSdkRoot
  }
  Write-Host "Android checks passed."
} else {
  Write-Host "Android wrapper not found; skipped."
}

Write-Host "Start server, then run: node tools/e2e/smoke.mjs"

