# Starts the backend for local development with the "dev" profile (creates demo accounts).
# Reads KEY=VALUE pairs from ../.env (git-ignored). Run from anywhere.
$root = Split-Path -Parent $PSScriptRoot
Get-Content "$root\.env" | Where-Object { $_ -match '^\s*[A-Za-z_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
}
$mvn = if (Test-Path "$root\tools\apache-maven-3.9.16\bin\mvn.cmd") { "$root\tools\apache-maven-3.9.16\bin\mvn.cmd" } else { "mvn" }
Set-Location "$root\backend"
& $mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
