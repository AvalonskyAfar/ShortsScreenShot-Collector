$ErrorActionPreference = 'Stop'

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $ProjectDir 'android'
$ToolsDir = Join-Path $ProjectDir '.tools'
$GradleVersion = '8.13'
$GradleHome = Join-Path $ToolsDir ('gradle-' + $GradleVersion)
$GradleExe = Join-Path $GradleHome 'bin\gradle.bat'
$AndroidSdk = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

if (-not (Test-Path -LiteralPath $AndroidSdk)) {
    throw ('Android SDK not found: ' + $AndroidSdk)
}

$JdkCandidates = @(
    $env:JAVA_HOME,
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
    (Join-Path $env:ProgramFiles 'Java\latest')
)
$JavaHome = $JdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) } | Select-Object -First 1
if (-not $JavaHome) { throw 'JDK 17 or newer not found' }

New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
if (-not (Test-Path -LiteralPath $GradleExe)) {
    $ZipPath = Join-Path $ToolsDir ('gradle-' + $GradleVersion + '-bin.zip')
    if (-not (Test-Path -LiteralPath $ZipPath)) {
        Write-Host ('Downloading Gradle ' + $GradleVersion + ' (first build only)...')
        $DownloadUrl = 'https://services.gradle.org/distributions/gradle-' + $GradleVersion + '-bin.zip'
        $Python = Get-Command py -ErrorAction SilentlyContinue
        if ($Python) {
            & $Python.Source -3 -c "import ssl,urllib.request; urllib.request.urlretrieve('$DownloadUrl', r'$ZipPath', reporthook=lambda n,b,t: print(f'{min(100, n*b*100//max(t,1))}%', end='\\r'))"
        } else {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -Uri $DownloadUrl -OutFile $ZipPath
        }
        if ($LASTEXITCODE -ne 0) { throw 'Gradle download failed' }
    }
    Write-Host 'Extracting Gradle...'
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $ToolsDir -Force
}

$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $ProjectDir '.gradle'
$env:ANDROID_USER_HOME = Join-Path $ProjectDir '.android'
$env:Path = (Join-Path $JavaHome 'bin') + ';' + (Join-Path $AndroidSdk 'platform-tools') + ';' + $env:Path

Write-Host 'Building Android APK...'
& $GradleExe --no-daemon --stacktrace -p $AndroidDir clean assembleDebug
if ($LASTEXITCODE -ne 0) { throw ('Android build failed, exit code ' + $LASTEXITCODE) }

$SourceApk = Join-Path $AndroidDir 'app\build\outputs\apk\debug\app-debug.apk'
$ReleaseDir = Join-Path $ProjectDir 'release'
$ReleaseApk = Join-Path $ReleaseDir 'ShortVideoCollector-debug.apk'
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
Copy-Item -LiteralPath $SourceApk -Destination $ReleaseApk -Force
Write-Host ('Build complete: ' + $ReleaseApk)
