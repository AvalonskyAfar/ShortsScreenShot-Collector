$ErrorActionPreference = 'Stop'

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$PythonCommand = Get-Command py -ErrorAction SilentlyContinue
if (-not $PythonCommand) { $PythonCommand = Get-Command python -ErrorAction SilentlyContinue }
$Python = if ($PythonCommand) { $PythonCommand.Source } else { $null }
if (-not $Python) { throw 'Python not found.' }

$BuildRoot = Join-Path $ProjectDir '.portable-build'
$DistRoot = Join-Path $BuildRoot 'dist'
$WorkRoot = Join-Path $BuildRoot 'work'
$SpecRoot = Join-Path $BuildRoot 'spec'
$Portable = Join-Path $DistRoot 'ShortVideoCollectorPortable'

if (Test-Path -LiteralPath $BuildRoot) { Remove-Item -LiteralPath $BuildRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $BuildRoot, $DistRoot, $WorkRoot, $SpecRoot | Out-Null

& $Python -m PyInstaller --noconfirm --clean --onedir --windowed `
    --name ShortVideoCollectorPortable `
    --distpath $DistRoot --workpath $WorkRoot --specpath $SpecRoot `
    (Join-Path $ProjectDir 'windows\receiver.py')
if ($LASTEXITCODE -ne 0) { throw 'PyInstaller build failed.' }

New-Item -ItemType Directory -Force -Path (Join-Path $Portable 'Android'), (Join-Path $Portable 'tools\adb') | Out-Null
Copy-Item -LiteralPath (Join-Path $ProjectDir 'release\ShortVideoCollector-debug.apk') -Destination (Join-Path $Portable 'Android\ShortVideoCollector-debug.apk') -Force
Copy-Item -LiteralPath (Join-Path $ProjectDir 'enable_usb_debug.ps1'), (Join-Path $ProjectDir 'disable_usb_debug.ps1'), (Join-Path $ProjectDir 'install_android.ps1') -Destination $Portable -Force
$UserGuide = Get-ChildItem -LiteralPath $ProjectDir -Filter '*.md' | Where-Object { $_.Name -ne 'README.md' } | Select-Object -First 1
if ($UserGuide) { Copy-Item -LiteralPath $UserGuide.FullName -Destination (Join-Path $Portable 'USER_GUIDE_CN.md') -Force }
Copy-Item -LiteralPath (Join-Path $ProjectDir 'portable_assets\start_receiver.bat') -Destination (Join-Path $Portable 'START_RECEIVER.bat') -Force
Copy-Item -LiteralPath (Join-Path $ProjectDir 'portable_assets\portable_guide_cn.txt') -Destination (Join-Path $Portable 'PORTABLE_GUIDE_CN.txt') -Force

$PlatformTools = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools'
Copy-Item -LiteralPath (Join-Path $PlatformTools 'adb.exe'), (Join-Path $PlatformTools 'AdbWinApi.dll'), (Join-Path $PlatformTools 'AdbWinUsbApi.dll'), (Join-Path $PlatformTools 'NOTICE.txt'), (Join-Path $PlatformTools 'source.properties') -Destination (Join-Path $Portable 'tools\adb') -Force

$ZipPath = Join-Path $ProjectDir 'ShortVideoCollectorPortable-Windows.zip'
if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force }
Compress-Archive -LiteralPath $Portable -DestinationPath $ZipPath -CompressionLevel Optimal
Write-Host ('Portable folder: ' + $Portable)
Write-Host ('Portable zip: ' + $ZipPath)
