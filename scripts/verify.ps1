# Runs every check a change must pass, the same ones CI runs (.github/workflows/ci.yml). Stops at the first failure.
#
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1            (everything)
#   powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -Offline   (skip the two checks that need the internet)
#
# Needs: the test database (README, "Tests"), the variables in .env, Java 25 and Node 20+.
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Step([string]$name, [scriptblock]$run) {
    Write-Host ''
    Write-Host "== $name" -ForegroundColor Cyan
    & $run
    if ($LASTEXITCODE -ne 0) { throw "$name FAILED (exit code $LASTEXITCODE)" }
}

if (-not (Test-Path "$root\.env")) { throw 'No .env file. Copy .env.example to .env and fill it in first (see README).' }
Get-Content "$root\.env" | Where-Object { $_ -match '^\s*[A-Za-z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
}
$mvn = if (Test-Path "$root\tools\apache-maven-3.9.16\bin\mvn.cmd") { "$root\tools\apache-maven-3.9.16\bin\mvn.cmd" } else { 'mvn' }

Push-Location "$root\backend"
try {
    # "verify" = compile with warnings as errors, all tests, Checkstyle, SpotBugs with Find Security Bugs.
    Step 'Backend: compile, tests, style, static analysis' { & $mvn -B verify }
    if (-not $Offline) {
        Step 'Backend: list runtime libraries' { & $mvn -B -q dependency:list '-DincludeScope=runtime' '-DoutputFile=target/runtime-dependencies.txt' }
        Step 'Backend: known vulnerabilities in runtime libraries' { node "$root\scripts\audit-dependencies.mjs" 'target/runtime-dependencies.txt' }
    }
} finally { Pop-Location }

Push-Location "$root\frontend"
try {
    Step 'Frontend: type check' { npm run typecheck }
    Step 'Frontend: lint' { npm run lint }
    Step 'Frontend: unit tests' { npm test }
    Step 'Frontend: production build' { npm run build }
    if (-not $Offline) {
        Step 'Frontend: known vulnerabilities in shipped packages' { npm audit --omit=dev }
    }
} finally { Pop-Location }

Write-Host ''
Write-Host 'All checks passed.' -ForegroundColor Green
