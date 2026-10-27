param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'local-env.ps1')) { . (Join-Path $PSScriptRoot 'local-env.ps1') }
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Specify JDK 17 with -JavaHome or JAVA_HOME.' }
if (-not $SdkRoot -or -not (Test-Path -LiteralPath (Join-Path $SdkRoot 'platforms\android-36\android.jar'))) { throw 'Specify Android SDK with Platform 36 using -SdkRoot or ANDROID_SDK_ROOT.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_SDK_ROOT = $SdkRoot
$taskProperty = 'sdk.dir=' + $SdkRoot.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), $taskProperty + "`n", [System.Text.UTF8Encoding]::new($false))
& (Join-Path $PSScriptRoot 'gradlew.bat') --console=plain assembleDebug lintDebug coreCheck
if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed.' }
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'apk') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $PSScriptRoot 'apk\FocusGuard-0.1.5-debug.apk')
Write-Host 'APK ready: apk\FocusGuard-0.1.5-debug.apk'
