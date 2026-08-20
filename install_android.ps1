$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ApkCandidates = @((Join-Path $ProjectDir 'release\ShortVideoCollector-debug.apk'), (Join-Path $ProjectDir 'Android\ShortVideoCollector-debug.apk'))
$Apk = $ApkCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$SdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), (Join-Path $env:SystemDrive 'Android\Sdk')) | Where-Object { $_ }
$AdbCandidates = @((Join-Path $ProjectDir 'tools\adb\adb.exe')) + @($SdkRoots | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' })
$Adb = $AdbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $Adb) {
    $FromPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($FromPath) { $Adb = $FromPath.Source }
}
if (-not $Adb) { throw 'ADB not found. Install Android SDK Platform-Tools in Android Studio SDK Manager.' }
if (-not $Apk) { throw 'APK not found.' }

& $Adb start-server
$Devices = @(& $Adb devices | Select-String "\tdevice$")
if ($Devices.Count -ne 1) { throw ('Expected one authorized Android device; found ' + $Devices.Count) }
& $Adb install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed' }
Write-Host 'Install complete.'
