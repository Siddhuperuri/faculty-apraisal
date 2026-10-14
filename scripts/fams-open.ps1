# OPEN THE SEASON: starts MySQL, the backend and the website, then lets the college network in.
# Run on the Windows server, in a PowerShell window opened with "Run as administrator":
#
#   powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\fams-open.ps1
#
# Close again with fams-close.ps1. See docs\windows-deployment.md.
$ErrorActionPreference = 'Stop'

$me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell with "Run as administrator" (right-click it in the Start menu) and run this again.'
}

$mysql = Get-Service | Where-Object { $_.Name -like 'MySQL*' } | Select-Object -First 1
if (-not $mysql) { throw 'No MySQL service was found. Is MySQL installed? (docs\windows-deployment.md, step 6)' }
foreach ($name in 'FAMS-Backend', 'FAMS-Frontend') {
    if (-not (Get-Service -Name $name -ErrorAction SilentlyContinue)) { throw "The service $name does not exist yet. Finish step 11 of docs\windows-deployment.md first." }
}

function Start-One([string]$name) {
    if ((Get-Service -Name $name).Status -ne 'Running') { Start-Service -Name $name }
    Write-Host "  $name is running"
}

Write-Host 'Starting MySQL...'
Start-One $mysql.Name

Write-Host 'Starting the backend (the first start after a new version can take a minute or two)...'
Start-One 'FAMS-Backend'
$deadline = (Get-Date).AddSeconds(240)
$up = $false
while (-not $up -and (Get-Date) -lt $deadline) {
    try {
        $answer = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8080/actuator/health' -TimeoutSec 5
        if ($answer.Content -match '"status"\s*:\s*"UP"') { $up = $true }
    } catch {
        # not up yet
    }
    if (-not $up) { Start-Sleep -Seconds 3 }
}
if (-not $up) {
    throw 'The backend did not report UP within 4 minutes. Read the last lines of C:\FAMS\logs\backend.log and see "If something goes wrong" in docs\windows-deployment.md.'
}
Write-Host '  the backend is UP'

Write-Host 'Starting the website...'
Start-One 'FAMS-Frontend'

Write-Host 'Letting the college network in (firewall rules on)...'
Enable-NetFirewallRule -DisplayName 'FAMS HTTP (college network)'

Write-Host ''
Write-Host 'OPEN. People on the college network can now use the site.' -ForegroundColor Green
