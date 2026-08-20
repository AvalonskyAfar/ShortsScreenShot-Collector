$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location (Join-Path $ProjectDir 'windows')
try {
    $Python = Get-Command py -ErrorAction SilentlyContinue
    if ($Python) {
        & $Python.Source -3 -m unittest -v test_receiver.py
    } else {
        & python -m unittest -v test_receiver.py
    }
    if ($LASTEXITCODE -ne 0) { throw 'Windows receiver tests failed' }
} finally {
    Pop-Location
}

