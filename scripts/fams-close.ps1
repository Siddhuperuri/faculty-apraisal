# CLOSE THE SEASON: shuts the network door, takes a final backup, then stops everything so the PC is a normal PC again.
# The data stays on the disk for next year. Run in a PowerShell window opened with "Run as administrator":
#
#   powershell -ExecutionPolicy Bypass -File C:\FAMS\app\scripts\fams-close.ps1
#
# If the final backup cannot be made, nothing is stopped (the network door stays shut): fix the problem and run it again,
# or add -SkipBackup if you have a good backup already. See docs\windows-deployment.md.
param(
    [switch]$SkipBackup,
    [string]$EnvFile = 'C:\FAMS\secrets\backup.env'
)
$ErrorActionPreference = 'Stop'

$me = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $me.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Open PowerShell with "Run as administrator" (right-click it in the Start menu) and run this again.'
}

Write-Host 'Shutting the network door (firewall rules off)...'
Disable-NetFirewallRule -DisplayName 'FAMS HTTP (college network)' -ErrorAction SilentlyContinue

if (-not $SkipBackup) {
    $line = Select-String -LiteralPath $EnvFile -Pattern '^\s*FAMS_BACKUP_DIR=(.+)$' | Select-Object -First 1
    if (-not $line) { throw "FAMS_BACKUP_DIR is not set in $EnvFile, so there is nowhere to put the final backup." }
    $to = $line.Matches[0].Groups[1].Value.Trim()
    if (-not (Test-Path -LiteralPath $to)) { throw "The backup folder $to does not exist. Is the backup disk plugged in?" }
    Write-Host "Final backup to $to ..."
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'backup.ps1') -To $to -EnvFile $EnvFile
    if ($LASTEXITCODE -ne 0) {
        throw 'The final backup FAILED, so nothing was stopped. Fix the problem above and run this again (or use -SkipBackup).'
    }
}

Write-Host 'Stopping the website and the backend...'
foreach ($name in 'FAMS-Frontend', 'FAMS-Backend') {
    $s = Get-Service -Name $name -ErrorAction SilentlyContinue
    if ($s -and $s.Status -ne 'Stopped') { Stop-Service -Name $name -Force }
    Write-Host "  $name stopped"
}

Write-Host 'Stopping MySQL...'
$mysql = Get-Service | Where-Object { $_.Name -like 'MySQL*' } | Select-Object -First 1
if ($mysql -and $mysql.Status -ne 'Stopped') { Stop-Service -Name $mysql.Name -Force }

Write-Host ''
Write-Host 'CLOSED. Everything is stopped and the data is kept on the disk.' -ForegroundColor Green
