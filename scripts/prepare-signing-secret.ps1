param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$CopyToClipboard
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -and (Test-Path -LiteralPath (Join-Path $taskRoot 'local-env.ps1'))) {
    . (Join-Path $taskRoot 'local-env.ps1')
}
if (-not (Test-Path -LiteralPath $KeystorePath -PathType Leaf)) { throw 'Existing signing keystore was not found. Do not generate a replacement key.' }
$taskKeytool = if ($JavaHome) { Join-Path $JavaHome 'bin\keytool.exe' } else { (Get-Command keytool -ErrorAction Stop).Source }
$taskOutput = & $taskKeytool '-J-Duser.language=en' '-J-Duser.country=US' -list -v -keystore $KeystorePath -storepass android -alias androiddebugkey 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the existing debug keystore.' }
$taskMatch = [regex]::Match($taskOutput, 'SHA256:\s*([A-Fa-f0-9:]{64,95})')
$taskExpected = 'ce10316317e5a582e6a80e8af2d08e82dffb20a41ed44de6b1853cdc575734c4'
if (-not $taskMatch.Success -or $taskMatch.Groups[1].Value.Replace(':', '').ToLowerInvariant() -ne $taskExpected) {
    throw 'The signing certificate differs from the installed FocusGuard APK.'
}
Write-Output 'Signing certificate verified.'
if ($CopyToClipboard) {
    $taskValue = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
    Set-Clipboard -Value $taskValue
    Remove-Variable taskValue
    Write-Output 'Copied the signing key to the clipboard. Paste it into the FOCUSGUARD_KEYSTORE_BASE64 GitHub Actions secret.'
    Write-Output 'The key was not printed or written to a file.'
} else {
    Write-Output 'Add -CopyToClipboard to prepare the GitHub secret value. Without this flag only the certificate is checked.'
}
