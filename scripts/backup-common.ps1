# Shared by backup.ps1 and restore.ps1: where the settings come from, how MySQL is reached and how stored files are checked.
# Not meant to be run on its own.

# A setting from the environment, else from the .env file next to the project (the same file the backend reads in
# development). On a server, run the script with the service's environment variables set.
function Get-Setting([string]$name, [hashtable]$fromFile, [string]$default = $null) {
    $value = [Environment]::GetEnvironmentVariable($name)
    if (-not $value -and $fromFile.ContainsKey($name)) { $value = $fromFile[$name] }
    if (-not $value) { $value = $default }
    return $value
}

function Read-EnvFile([string]$path) {
    $values = @{}
    if ($path -and (Test-Path -LiteralPath $path)) {
        Get-Content -LiteralPath $path -Encoding UTF8 | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
            $k, $v = $_ -split '=', 2
            $values[$k.Trim().TrimStart([char]0xFEFF)] = $v.Trim()
        }
    }
    return $values
}

# jdbc:mysql://host:port/database?options  ->  host, port, database
function ConvertFrom-JdbcUrl([string]$url) {
    if ($url -notmatch '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<db>[^?]+)') {
        throw "FAMS_DB_URL is not a MySQL JDBC URL (jdbc:mysql://host:port/database)."
    }
    $port = '3306'
    if ($Matches['port']) { $port = $Matches['port'] }
    return @{ Host = $Matches['host']; Port = $port; Database = $Matches['db'] }
}

function Find-MySqlTool([string]$name) {
    $onPath = Get-Command $name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    foreach ($dir in @('C:\Program Files\MySQL\MySQL Server 8.4\bin', 'C:\Program Files\MySQL\MySQL Server 8.0\bin')) {
        $candidate = Join-Path $dir "$name.exe"
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    throw "$name was not found. Install the MySQL client tools or add them to PATH."
}

# The password goes to the MySQL tools in a private options file, never on a command line (where other users of the
# machine could read it). Returns the file; the caller removes it.
function New-MySqlOptionsFile([hashtable]$db, [string]$user, [string]$password) {
    $file = [System.IO.Path]::GetTempFileName()
    $escaped = $password.Replace('\', '\\').Replace('"', '\"')
    $lines = @('[client]', "host=$($db.Host)", "port=$($db.Port)", "user=$user", "password=`"$escaped`"")
    [System.IO.File]::WriteAllLines($file, $lines, (New-Object System.Text.UTF8Encoding($false)))
    return $file
}

# Stored files are named by a random key and kept under a folder named after the key's first two characters.
function Get-StoredFilePath([string]$root, [string]$key) {
    return Join-Path (Join-Path $root $key.Substring(0, 2)) $key
}

# Copies every stored file that the destination does not have yet. Stored files never change once written, so a file
# that is already there is the same file.
function Copy-StoredFiles([string]$from, [string]$to) {
    $copied = 0
    if (-not (Test-Path -LiteralPath $from)) { return $copied }
    Get-ChildItem -LiteralPath $from -Recurse -File | Where-Object { $_.Extension -ne '.part' } | ForEach-Object {
        $relative = $_.FullName.Substring((Resolve-Path -LiteralPath $from).Path.Length).TrimStart('\', '/')
        $target = Join-Path $to $relative
        if (-not (Test-Path -LiteralPath $target)) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target
            $copied++
        }
    }
    return $copied
}

# Checks every manifest entry (kind, key, sha256, bytes) against the files under $root. Returns the problems found.
function Test-StoredFiles([object[]]$manifest, [string]$root) {
    $problems = @()
    foreach ($entry in $manifest) {
        $path = Get-StoredFilePath $root $entry.key
        if (-not (Test-Path -LiteralPath $path)) {
            $problems += "missing: $($entry.kind) $($entry.key)"
        } elseif ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            $problems += "altered (checksum differs): $($entry.kind) $($entry.key)"
        }
    }
    return $problems
}

# The two small files the administrator console reads (see docs\deployment.md). Written to a temporary name and then
# moved, so the console never reads half a file. A failure to write them never turns a good backup into a failed one.
function Write-StatusFile([string]$to, [string]$name, [hashtable]$content) {
    try {
        if (-not (Test-Path -LiteralPath $to -PathType Container)) { return }
        $target = Join-Path $to $name
        $temp = "$target.part"
        [System.IO.File]::WriteAllText($temp, ($content | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $temp -Destination $target -Force
    } catch {
        Write-Host "Could not write $name to ${to}: $($_.Exception.Message)"
    }
}

function Write-BackupSuccess([string]$to, [string]$folder, [long]$databaseBytes, [int]$fileCount) {
    $total = (Get-ChildItem -LiteralPath $folder -Recurse -File | Measure-Object -Property Length -Sum).Sum
    Write-StatusFile $to 'latest.json' @{
        finishedAt = (Get-Date).ToString('o'); folder = (Split-Path -Leaf $folder)
        databaseBytes = $databaseBytes; totalBytes = [long]$total; files = $fileCount
    }
    # A good backup clears the earlier failure's note, so the console does not go on reporting it.
    Remove-Item -LiteralPath (Join-Path $to 'last-failure.json') -Force -ErrorAction SilentlyContinue
}

function Write-BackupFailure([string]$to, [string]$message) {
    Write-StatusFile $to 'last-failure.json' @{ failedAt = (Get-Date).ToString('o'); message = $message }
}
