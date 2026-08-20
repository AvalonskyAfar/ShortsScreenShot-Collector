$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkRoots = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'), (Join-Path $env:SystemDrive 'Android\Sdk')) | Where-Object { $_ }
$AdbCandidates = @((Join-Path $ProjectDir 'tools\adb\adb.exe')) + @($SdkRoots | ForEach-Object { Join-Path $_ 'platform-tools\adb.exe' })
$Adb = $AdbCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $Adb) {
    $FromPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($FromPath) { $Adb = $FromPath.Source }
}
if (-not $Adb) { throw 'ADB not found. In Android Studio open SDK Manager > SDK Tools, install Android SDK Platform-Tools, then run this script again.' }
& $Adb start-server
$Devices = @(& $Adb devices | Select-String "\tdevice$")
if ($Devices.Count -ne 1) { throw ('Connect and authorize exactly one Android phone by USB. Found ' + $Devices.Count) }
& $Adb reverse tcp:8765 tcp:8765
if ($LASTEXITCODE -ne 0) { throw 'ADB reverse setup failed' }
Write-Host 'USB channel enabled: phone 127.0.0.1:8765 -> Windows 127.0.0.1:8765'
Write-Host 'Keep USB connected. In the Android app use address 127.0.0.1 and port 8765.'
