# Backs up everything needed to restore an installation, in one folder:
#
#   database.sql    the whole database (accounts, appraisals, history, audit trail)
#   files\          the stored files: the issued official reports (FAMS_STORAGE_DIR)
#   manifest.csv    every stored file the database refers to, with its SHA-256
#
# and then checks the copy: every file the database refers to must be in it, unaltered. The backup fails (exit code 1)
# if one is missing or does not match, so a backup that looks complete is complete.
#
#   powershell -ExecutionPolicy Bypass -File scripts\backup.ps1 -To D:\backups
#
# Settings are the backend's own: FAMS_DB_URL, FAMS_DB_USER, FAMS_DB_PASSWORD and FAMS_STORAGE_DIR, from the environment
# or from .env. The application may keep running. Restore with scripts\restore.ps1. See docs\deployment.md.
#
# When it finishes it leaves latest.json (after a good backup) or last-failure.json (after a failed one) in the folder
# given with -To. The administrator console reads those two files to show when the last backup was made (set
# FAMS_BACKUP_DIR for the backend to the same folder).
param(
    [Parameter(Mandatory = $true)][string]$To,
    [string]$EnvFile
)
$ErrorActionPreference = 'Stop'
$script:statusRecorded = $false
. (Join-Path $PSScriptRoot 'backup-common.ps1')

try {
$root = Split-Path -Parent $PSScriptRoot
if (-not $EnvFile) { $EnvFile = Join-Path $root '.env' }
$fromFile = Read-EnvFile $EnvFile

$url = Get-Setting 'FAMS_DB_URL' $fromFile
$user = Get-Setting 'FAMS_DB_USER' $fromFile 'fams'
$password = Get-Setting 'FAMS_DB_PASSWORD' $fromFile
# In development the backend keeps files in backend\storage\documents unless told otherwise.
$storage = Get-Setting 'FAMS_STORAGE_DIR' $fromFile (Join-Path $root 'backend\storage\documents')
if (-not $url -or -not $password) { throw 'FAMS_DB_URL and FAMS_DB_PASSWORD must be set (environment or .env).' }
$db = ConvertFrom-JdbcUrl $url

$mysqldump = Find-MySqlTool 'mysqldump'
$mysql = Find-MySqlTool 'mysql'

$folder = Join-Path $To ("fams-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$files = Join-Path $folder 'files'
New-Item -ItemType Directory -Force -Path $files | Out-Null
Write-Host "Backing up database '$($db.Database)' and $storage"
Write-Host "  to $folder"

$options = New-MySqlOptionsFile $db $user $password
try {
    # 1. What the database refers to now. A file is always written before the database mentions it, so every one of
    #    these exists already.
    $query = "SELECT 'report', storage_key, checksum, size_bytes FROM appraisal_reports"
    $rows = & $mysql "--defaults-extra-file=$options" --batch --skip-column-names $db.Database -e $query
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the list of stored files from the database.' }
    $manifest = @($rows | Where-Object { $_ } | ForEach-Object {
        $kind, $key, $sha, $bytes = $_ -split "`t"
        [pscustomobject]@{ kind = $kind; key = $key; sha256 = $sha; bytes = $bytes }
    })

    # 2. The files, then the database, then any file that arrived in between: whatever the dump refers to is in the copy.
    $copied = Copy-StoredFiles $storage $files
    & $mysqldump "--defaults-extra-file=$options" --single-transaction --routines --triggers --hex-blob `
        --no-tablespaces --set-gtid-purged=OFF "--result-file=$(Join-Path $folder 'database.sql')" $db.Database
    if ($LASTEXITCODE -ne 0) { throw 'mysqldump failed; the backup is not complete.' }
    $copied += Copy-StoredFiles $storage $files
} finally {
    Remove-Item -LiteralPath $options -Force -ErrorAction SilentlyContinue
}

$manifest | Export-Csv -LiteralPath (Join-Path $folder 'manifest.csv') -NoTypeInformation -Encoding UTF8

# 3. Check the copy against the database's own record of each file.
$problems = @(Test-StoredFiles $manifest $files)
$dumpSize = (Get-Item -LiteralPath (Join-Path $folder 'database.sql')).Length
Write-Host ("  database.sql   {0:N0} bytes" -f $dumpSize)
Write-Host "  files          $copied copied, $($manifest.Count) referred to by the database"
if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host "BACKUP INCOMPLETE: $($problems.Count) stored file(s) the database refers to are not in the backup as recorded:"
    $problems | ForEach-Object { Write-Host "  $_" }
    Write-Host "Check that FAMS_STORAGE_DIR ($storage) is the directory the backend really uses."
    Write-BackupFailure $To "$($problems.Count) stored file(s) the database refers to are not in the backup as recorded."
    $script:statusRecorded = $true
    exit 1
}
Write-Host 'Backup complete and verified: every stored file the database refers to is present and unaltered.'
Write-BackupSuccess $To $folder $dumpSize $manifest.Count
} catch {
    if (-not $script:statusRecorded) { Write-BackupFailure $To $_.Exception.Message }
    throw
}
