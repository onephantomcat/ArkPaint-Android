param(
    [Parameter(Mandatory = $false)]
    [string]$AndroidSdk = $env:ANDROID_SDK_ROOT
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$androidRoot = Join-Path $repositoryRoot 'android'
$localProperties = Join-Path $androidRoot 'local.properties'

if (-not (Test-Path -LiteralPath $localProperties)) {
    if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
        throw 'Android SDK not found. Pass -AndroidSdk or set ANDROID_SDK_ROOT.'
    }
    $resolvedSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path.Replace('\', '/')
    Set-Content -LiteralPath $localProperties -Value "sdk.dir=$resolvedSdk" -Encoding ascii
}

Push-Location $androidRoot
try {
    & '.\gradlew.bat' --no-daemon testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Android build failed with exit code $LASTEXITCODE"
    }
    $apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    Get-Item -LiteralPath $apk
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    Pop-Location
}
