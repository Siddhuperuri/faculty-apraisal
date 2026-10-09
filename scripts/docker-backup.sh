#!/usr/bin/env bash
# Backs up a Docker deployment (docker-compose.production.yml) into one folder, the same shape scripts/backup.ps1 makes:
#
#   database.sql    the whole database (accounts, appraisals, history, audit trail)
#   files/          the stored files: the issued official reports
#   manifest.csv    every stored file the database refers to, with its SHA-256
#
# and then checks the copy: every file the database refers to must be in it, unaltered. The backup fails (exit code 1)
# if one is missing or does not match, so a backup that looks complete is complete.
#
#   scripts/docker-backup.sh /srv/fams-backups
#
# Run it on the Docker host, from anywhere, while the stack is up (users may keep working). The folder must be the one
# FAMS_BACKUP_DIR_HOST names in .env.docker, on a disk or machine other than the one that holds the data: a copy on the
# same disk is not a backup. When it finishes it leaves latest.json (after a good backup) or last-failure.json (after
# a failed one) in that folder; the administrator console reads them to show when the last backup was made.
# Restore with scripts/docker-restore.sh. See docs/docker-deployment.md.
#
# FAMS_ENV_FILE overrides the settings file (default: .env.docker next to docker-compose.production.yml).
set -Eeuo pipefail

to="${1:-}"
if [ -z "$to" ] || [ ! -d "$to" ]; then
    echo "Usage: $0 <existing folder to put the backup in>" >&2
    exit 2
fi
# pwd -W (Git Bash on Windows) gives a path Docker understands; on Linux plain pwd is used.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
env_file="${FAMS_ENV_FILE:-$root/.env.docker}"
[ -f "$env_file" ] || { echo "Settings file not found: $env_file" >&2; exit 2; }
dc() { docker compose -f "$root/docker-compose.production.yml" --env-file "$env_file" "$@"; }

folder="$to/fams-backup-$(date +%Y%m%d-%H%M%S)"
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# The two small files the administrator console reads. Written to a temporary name and then moved, so the console never
# reads half a file. Failing to write them never turns a good backup into a failed one.
write_status() { # <file name> <json>
    { printf '%s' "$2" > "$to/$1.part" && mv -f "$to/$1.part" "$to/$1"; } 2>/dev/null \
        || echo "Could not write $1 to $to" >&2
}
failure() {
    trap - ERR
    local message="$1"
    echo "BACKUP FAILED: $message" >&2
    message="${message//\\/\\\\}"; message="${message//\"/\\\"}"
    write_status last-failure.json "{\"failedAt\":\"$(now_utc)\",\"message\":\"$message\"}"
    exit 1
}
trap 'failure "The backup stopped before it finished (see the messages above)."' ERR

mkdir -p "$folder/files"
echo "Backing up the FAMS database and stored files"
echo "  to $folder"

# The root account is reachable inside the MySQL container only; its password is that container's own variable and
# never appears on a command line.
mysql_root() { dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"' sh "$@"; }

# 1. What the database refers to now. A file is always written before the database mentions it, so every one of these
#    exists already.
printf '%s\n' "SELECT 'report', storage_key, checksum, size_bytes FROM appraisal_reports" \
    | mysql_root mysql -uroot --batch --skip-column-names fams > "$folder/manifest.tsv"
{
    printf '"kind","key","sha256","bytes"\n'
    awk -F'\t' 'NF >= 4 { printf "\"%s\",\"%s\",\"%s\",\"%s\"\n", $1, $2, $3, $4 }' "$folder/manifest.tsv"
} > "$folder/manifest.csv"
rm -f "$folder/manifest.tsv"
referred=$(($(wc -l < "$folder/manifest.csv") - 1))

# 2. The files, then the database, then any file that arrived in between: whatever the dump refers to is in the copy.
copy_files() {
    dc exec -T backend tar -C /data/documents --exclude='*.part' -cf - . | tar -C "$folder/files" -xf -
}
copy_files
mysql_root mysqldump -uroot --single-transaction --routines --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF \
    --default-character-set=utf8mb4 fams > "$folder/database.sql"
copy_files

tail -n 3 "$folder/database.sql" | grep -q 'Dump completed' || failure "mysqldump did not finish; the backup is not complete."

# 3. Check the copy against the database's own record of each file.
problems=0
while IFS=, read -r _kind key sha _bytes; do
    key="${key//\"/}"; sha="${sha//\"/}"
    path="$folder/files/${key:0:2}/$key"
    if [ ! -f "$path" ]; then
        echo "  missing: $key" >&2; problems=$((problems + 1))
    elif [ "$(sha256sum "$path" | cut -d' ' -f1)" != "$sha" ]; then
        echo "  altered (checksum differs): $key" >&2; problems=$((problems + 1))
    fi
done < <(tail -n +2 "$folder/manifest.csv")

dump_bytes=$(wc -c < "$folder/database.sql")
total_bytes=$(du -sb "$folder" | cut -f1)
echo "  database.sql   $dump_bytes bytes"
echo "  files          $referred referred to by the database"
if [ "$problems" -gt 0 ]; then
    failure "$problems stored file(s) the database refers to are not in the backup as recorded."
fi

echo "Backup complete and verified: every stored file the database refers to is present and unaltered."
trap - ERR
write_status latest.json "{\"finishedAt\":\"$(now_utc)\",\"folder\":\"$(basename "$folder")\",\"databaseBytes\":$dump_bytes,\"totalBytes\":$total_bytes,\"files\":$referred}"
rm -f "$to/last-failure.json"
