# Sharpr desktop launcher: creates .venv on first run, installs deps, starts the app.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$venvPy = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

if (-not (Test-Path $venvPy)) {
    $py = $null
    foreach ($c in @("py -3.12", "py -3.11", "python")) {
        try { $v = Invoke-Expression "$c --version" 2>$null; if ($v -match "Python 3\.(11|12)") { $py = $c; break } } catch {}
    }
    if (-not $py) { throw "Python 3.11 or 3.12 is required (https://www.python.org/downloads/)." }
    Write-Host "Creating virtual environment with $py ..."
    Invoke-Expression "$py -m venv .venv"
    & $venvPy -m pip install --upgrade pip
    & $venvPy -m pip install -r requirements.txt
}

& $venvPy main.py @args
