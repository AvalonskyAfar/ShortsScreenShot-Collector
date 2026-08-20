$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), (Join-Path $env:SystemDrive 'Android\Sdk')) | Where-Object { $_ }
$AdbCandidates = @((Join-Path $ProjectDir 'tools\adb\adb.exe')) + @($SdkRoots | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' })
$Adb = $AdbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $Adb) {
    $FromPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($FromPath) { $Adb = $FromPath.Source }
}
if (-not $Adb) { throw 'ADB not found. Install Android SDK Platform-Tools in Android Studio SDK Manager.' }
& $Adb reverse --remove tcp:8765
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse channel removal failed' }
Write-Host 'USB channel disabled.'
