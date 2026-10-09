# Restores a backup made by backup.ps1: loads database.sql into the database named by FAMS_DB_URL and puts the stored
# files back into FAMS_STORAGE_DIR, then checks that every file the database refers to is there, unaltered.
#
#   1. Stop the backend.
#   2. powershell -ExecutionPolicy Bypass -File scripts\restore.ps1 -From D:\backups\fams-backup-20261006-021500 -Overwrite
#   3. Start the backend (it applies any newer database migrations by itself).
#
# -Overwrite is required because the database is REPLACED by the one in the backup. Without it the script only checks
# the backup and says what it would do. Files are only ever added to FAMS_STORAGE_DIR, never removed.
#
# The database account must be allowed to create tables and triggers: SPRING_FLYWAY_USER / SPRING_FLYWAY_PASSWORD are used
# when set (the migration account of docs\deployment.md), otherwise FAMS_DB_USER / FAMS_DB_PASSWORD.
param(
    [Parameter(Mandatory = $true)][string]$From,
    [switch]$Overwrite,
    [string]$EnvFile
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'backup-common.ps1')

$root = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $root '.env' }
$fromFile = Read-EnvFile $EnvFile

$dump = Join-Path $From 'database.sql'
$files = Join-Path $From 'files'
$manifestFile = Join-Path $From 'manifest.csv'
foreach ($needed in @($dump, $manifestFile)) {
    if (-not (Test-Path -LiteralPath $needed)) { throw "Not a backup made by backup.ps1: $needed is missing." }
}
$manifest = @(Import-Csv -LiteralPath $manifestFile)

$url = Get-Setting 'FAMS_DB_URL' $fromFile
$user = Get-Setting 'SPRING_FLYWAY_USER' $fromFile (Get-Setting 'FAMS_DB_USER' $fromFile 'fams')
$password = Get-Setting 'SPRING_FLYWAY_PASSWORD' $fromFile (Get-Setting 'FAMS_DB_PASSWORD' $fromFile)
$storage = Get-Setting 'FAMS_STORAGE_DIR' $fromFile (Join-Path $root 'backend\storage\documents')
if (-not $url -or -not $password) { throw 'FAMS_DB_URL and a database password must be set (environment or .env).' }
$db = ConvertFrom-JdbcUrl $url

# The backup itself first: restoring from a damaged one would replace good data with bad.
$problems = @(Test-StoredFiles $manifest $files)
if ($problems.Count -gt 0) {
    Write-Host "This backup is damaged: $($problems.Count) stored file(s) are not as recorded in its manifest."
    $problems | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "Backup $From is intact: database.sql and $($manifest.Count) stored file(s)."

if (-not $Overwrite) {
    Write-Host ''
    Write-Host "Nothing was changed. To restore, stop the backend and run again with -Overwrite. That will:"
    Write-Host "  - REPLACE database '$($db.Database)' on $($db.Host):$($db.Port) with the one in the backup"
    Write-Host "  - add the backup's files to $storage"
    exit 0
}

$mysql = Find-MySqlTool 'mysql'
$options = New-MySqlOptionsFile $db $user $password
try {
    Write-Host "Loading database.sql into '$($db.Database)' ..."
    # "source" reads the file inside the client, so its bytes are not re-encoded by the shell on the way in.
    & $mysql "--defaults-extra-file=$options" --default-character-set=utf8mb4 $db.Database -e ("source " + $dump.Replace('\', '/'))
    if ($LASTEXITCODE -ne 0) { throw 'Loading the database failed. The database may be incomplete: fix the cause and run the restore again.' }
} finally {
    Remove-Item -LiteralPath $options -Force -ErrorAction SilentlyContinue
}

New-Item -ItemType Directory -Force -Path $storage | Out-Null
$copied = Copy-StoredFiles $files $storage
Write-Host "Stored files: $copied put back into $storage"

$problems = @(Test-StoredFiles $manifest $storage)
if ($problems.Count -gt 0) {
    Write-Host "RESTORE INCOMPLETE: $($problems.Count) stored file(s) in $storage are not as the database records them:"
    $problems | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host 'Restore complete and verified. Start the backend.'
