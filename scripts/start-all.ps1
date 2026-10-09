# Starts everything for local development: the project's MySQL (port 3307), the backend (port 8080) and the frontend
# (port 3000). Anything that is already running is left alone, so it is safe to run twice.
#
#   powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
#
# Logs go to tools\ (mysqld.log, backend.log / backend.err, frontend.log / frontend.err).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root 'tools'

function Test-Port([int]$port) {
    [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Until([string]$what, [scriptblock]$ready, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try { if (& $ready) { Write-Host "  $what is up"; return } } catch { }
        Start-Sleep -Seconds 2
    }
    throw "$what did not come up within $seconds seconds. See the logs in $tools"
}

function Test-Http([string]$url) {
    try { (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 4).StatusCode -eq 200 } catch { $false }
}

# ---- sanity checks -------------------------------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $root '.env'))) {
    throw "No .env file. Copy .env.example to .env and fill in the passwords first (see README)."
}
$mysqld = @('C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqld.exe', 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe') |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $mysqld) { throw 'MySQL Server is not installed (looked in C:\Program Files\MySQL).' }
if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) {
    throw "Frontend packages are not installed. Run:  cd frontend ; npm install"
}

# ---- 1. database ---------------------------------------------------------------------------------------------------
Write-Host 'Database (MySQL on port 3307)'
if (Test-Port 3307) {
    Write-Host '  already running'
} else {
    # log_bin_trust_function_creators is needed because the schema uses triggers (see README).
    # bind-address keeps the development database off the network: without it MySQL listens on every interface,
    # and anyone on the same network could try the port.
    Start-Process -FilePath $mysqld -WindowStyle Hidden `
        -ArgumentList "--datadir=`"$tools\mysql-data`"", '--port=3307', '--bind-address=127.0.0.1', '--mysqlx=OFF', '--log-bin-trust-function-creators=1', '--console' `
        -RedirectStandardError (Join-Path $tools 'mysqld.log') | Out-Null
    Wait-Until 'MySQL' { Test-Port 3307 } 90
}

# ---- 2. backend ----------------------------------------------------------------------------------------------------
Write-Host 'Backend (Spring Boot on port 8080)'
if (Test-Http 'http://localhost:8080/actuator/health') {
    Write-Host '  already running'
} else {
    Start-Process -FilePath powershell.exe -WindowStyle Hidden `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'dev-backend.ps1') `
        -RedirectStandardOutput (Join-Path $tools 'backend.log') -RedirectStandardError (Join-Path $tools 'backend.err') | Out-Null
    Wait-Until 'Backend' { Test-Http 'http://localhost:8080/actuator/health' } 180
}

# ---- 3. frontend ---------------------------------------------------------------------------------------------------
Write-Host 'Frontend (Next.js on port 3000)'
if (Test-Http 'http://localhost:3000/login') {
    Write-Host '  already running'
} else {
    Start-Process -FilePath cmd.exe -WindowStyle Hidden -WorkingDirectory (Join-Path $root 'frontend') `
        -ArgumentList '/c', 'npm run dev' `
        -RedirectStandardOutput (Join-Path $tools 'frontend.log') -RedirectStandardError (Join-Path $tools 'frontend.err') | Out-Null
    Wait-Until 'Frontend' { Test-Http 'http://localhost:3000/login' } 120
}

Write-Host ''
Write-Host 'Everything is running.  Open  http://localhost:3000' -ForegroundColor Green
Write-Host 'Demo sign-ins (password = the standard one, Srivasavi@123, unless FAMS_DEFAULT_PASSWORD in .env says otherwise):'
Write-Host '  kavitha.reddy@dev.local (administrator)   venkat.rao@dev.local (Principal)   padmaja.sharma@dev.local (HoD, CSE)'
Write-Host '  faculty, one per cadre:  anil.kumar  priya.nair  rahul.verma  lakshmi.iyer  srinivas.murthy  (all @dev.local)'
Write-Host 'To stop:  powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1'
