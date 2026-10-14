# Builds the folder (and zip) that is copied to the Windows server: the backend jar, the built website, the backup and
# season scripts, and the deployment guide. Run it on a Windows machine with internet access, Java 25, Maven and Node 20+
# (the development machine). The server itself needs none of the build tools.
#
#   powershell -ExecutionPolicy Bypass -File scripts\make-windows-release.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\make-windows-release.ps1 -Out D:\release
#   powershell -ExecutionPolicy Bypass -File scripts\make-windows-release.ps1 -Version 2     # appraisal-release-2.zip
#
# Build on Windows for Windows: the website's image resizer (sharp) is a native library, and the one that is packed
# in is the one for the machine that built it. Step by step: docs\windows-deployment.md.
param(
    [string]$Out,
    # The release number: the folder and the zip are called appraisal-release-<Version>.
    [string]$Version = '1'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Out) { $Out = Join-Path $root 'release' }

function Step([string]$title, [scriptblock]$body) {
    # Not called $name: the step blocks read the script's $name (the release name), and a parameter of that name would hide it.
    Write-Host ""
    Write-Host "== $title" -ForegroundColor Cyan
    & $body
}
function Check([string]$what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE). Nothing was released." }
}

$mvn = if (Test-Path "$root\tools\apache-maven-3.9.16\bin\mvn.cmd") { "$root\tools\apache-maven-3.9.16\bin\mvn.cmd" } else { 'mvn' }
$name = "appraisal-release-$Version"
$dest = Join-Path $Out $name

Step 'Backend: build the jar (tests are run by scripts\verify.ps1, not here)' {
    Push-Location (Join-Path $root 'backend')
    try {
        # The -D flag is quoted: PowerShell would otherwise split it at the dot.
        & $mvn -B -ntp '-Dmaven.test.skip=true' clean package
        Check 'The backend build'
    } finally { Pop-Location }
}

Step 'Website: install libraries and build' {
    Push-Location (Join-Path $root 'frontend')
    try {
        & npm ci --no-audit --no-fund
        Check 'npm ci'
        # BACKEND_URL is written into the build. On the server the backend is always on this same machine, port 8080.
        $env:BACKEND_URL = 'http://127.0.0.1:8080'
        $env:NEXT_OUTPUT = 'standalone'
        $env:NEXT_TELEMETRY_DISABLED = '1'
        & npm run build
        Check 'The website build'
    } finally {
        Remove-Item Env:\BACKEND_URL, Env:\NEXT_OUTPUT, Env:\NEXT_TELEMETRY_DISABLED -ErrorAction SilentlyContinue
        Pop-Location
    }
}

Step "Assemble $dest" {
    $jar = Get-ChildItem (Join-Path $root 'backend\target') -Filter 'fams-backend-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
    if (-not $jar) { throw 'The backend jar was not found in backend\target.' }
    $standalone = Join-Path $root 'frontend\.next\standalone'
    if (-not (Test-Path (Join-Path $standalone 'server.js'))) { throw 'frontend\.next\standalone\server.js is missing: the website was not built as "standalone".' }

    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
    New-Item -ItemType Directory -Force -Path (Join-Path $dest 'backend'), (Join-Path $dest 'frontend'), (Join-Path $dest 'scripts'), (Join-Path $dest 'docs') | Out-Null

    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $dest 'backend\fams-backend.jar')
    Copy-Item -Path (Join-Path $standalone '*') -Destination (Join-Path $dest 'frontend') -Recurse -Force
    New-Item -ItemType Directory -Force -Path (Join-Path $dest 'frontend\.next') | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'frontend\.next\static') -Destination (Join-Path $dest 'frontend\.next') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $root 'frontend\public') -Destination (Join-Path $dest 'frontend') -Recurse -Force

    foreach ($s in 'backup.ps1', 'backup-common.ps1', 'restore.ps1', 'fams-open.ps1', 'fams-close.ps1') {
        Copy-Item -LiteralPath (Join-Path $root "scripts\$s") -Destination (Join-Path $dest 'scripts')
    }
    Copy-Item -LiteralPath (Join-Path $root 'docs\windows-deployment.md') -Destination (Join-Path $dest 'docs')

    $commit = (& git -C $root rev-parse --short HEAD 2>$null)
    $dirty = (& git -C $root status --porcelain 2>$null)
    $note = if ($dirty) { ' (with uncommitted changes)' } else { '' }
    "$name, built $(Get-Date -Format 'yyyy-MM-dd HH:mm') from commit $commit$note" | Set-Content -LiteralPath (Join-Path $dest 'VERSION.txt') -Encoding ASCII
    @"
$name
=====================

START HERE:  docs\windows-deployment.md   (open it in Notepad; begin at section 3)

What is in this folder
  backendams-backend.jar     the application server (needs Java 25)
  frontend\                    the built website (needs Node.js 22)
  scripts\                     open / close the season, backup, restore
  docs\windows-deployment.md   the step-by-step guide

What is NOT in it (free downloads, listed in section 1 of the guide):
  Java 25 (Temurin), MySQL 8.4, Node.js 22, NSSM

Copy this whole folder to C:\FAMSpp on the server PC (step 7 of the guide does it for you).
"@ | Set-Content -LiteralPath (Join-Path $dest 'README.txt') -Encoding ASCII
}

Step 'Zip it' {
    $zip = "$dest.zip"
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
    Compress-Archive -Path $dest -DestinationPath $zip -CompressionLevel Optimal
    $mb = [math]::Round((Get-Item -LiteralPath $zip).Length / 1MB, 1)
    Write-Host ""
    $hash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLower()
    "$hash  $name.zip" | Set-Content -LiteralPath "$zip.sha256" -Encoding ASCII
    Write-Host "Done: $zip ($mb MB)" -ForegroundColor Green
    Write-Host "SHA-256: $hash"
    Write-Host "Copy the zip to the server (USB stick) and follow docs\windows-deployment.md."
}
