# Stops the frontend and the backend. The database is left running unless you ask for it:
#
#   powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1             (frontend + backend)
#   powershell -ExecutionPolicy Bypass -File scripts\stop-all.ps1 -Database   (also MySQL on port 3307)
param([switch]$Database)
$ErrorActionPreference = 'Continue'

function Stop-ListenerOn([int]$port, [string]$name) {
    $owners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    if (-not $owners) { Write-Host "$name was not running"; return }
    foreach ($id in $owners) { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue }
    Write-Host "$name stopped"
}

# Next.js runs as several node processes; stop every one that belongs to this project's frontend.
$frontend = (Join-Path (Split-Path -Parent $PSScriptRoot) 'frontend').ToLowerInvariant()
$nodes = Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine.ToLowerInvariant().Contains($frontend) }
if ($nodes) { $nodes | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }; Write-Host 'Frontend stopped' }
else { Stop-ListenerOn 3000 'Frontend' }

Stop-ListenerOn 8080 'Backend'

if ($Database) { Stop-ListenerOn 3307 'Database' } else { Write-Host 'Database left running (use -Database to stop it too)' }
