#!/usr/bin/env bash
# Restores a backup made by scripts/docker-backup.sh (or scripts/backup.ps1) into a Docker deployment
# (docker-compose.production.yml): loads database.sql into the fams database and puts the stored files back, then checks
# that every file the database refers to is there, unaltered.
#
#   scripts/docker-restore.sh /srv/fams-backups/fams-backup-20261006-021500               checks the backup, changes nothing
#   scripts/docker-restore.sh /srv/fams-backups/fams-backup-20261006-021500 --overwrite   restores
#
# --overwrite is required because the database is DROPPED AND REPLACED by the one in the backup (everything entered since
# the backup is lost). Take a fresh backup first if there is anything worth keeping. Files are only ever added to the
# stored-files volume, never removed. The backend is stopped for the restore and left stopped; start it afterwards
# (it applies any newer database migrations by itself):
#
#   docker compose -f docker-compose.production.yml --env-file .env.docker up -d
#
# The stack's MySQL container must be able to start (the volumes, .env.docker and TLS files of a normal installation);
# on a new server, install the stack first (docs/docker-deployment.md), then restore. See that document for a restore test.
#
# FAMS_ENV_FILE overrides the settings file (default: .env.docker next to docker-compose.production.yml).
set -Eeuo pipefail

from="${1:-}"
overwrite="no"
[ "${2:-}" = "--overwrite" ] && overwrite="yes"
if [ -z "$from" ] || { [ -n "${2:-}" ] && [ "$overwrite" = "no" ]; }; then
    echo "Usage: $0 <backup folder> [--overwrite]" >&2
    exit 2
fi
# pwd -W (Git Bash on Windows) gives a path Docker understands; on Linux plain pwd is used.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && { pwd -W 2>/dev/null || pwd; })"
env_file="${FAMS_ENV_FILE:-$root/.env.docker}"
[ -f "$env_file" ] || { echo "Settings file not found: $env_file" >&2; exit 2; }
dc() { docker compose -f "$root/docker-compose.production.yml" --env-file "$env_file" "$@"; }
mysql_root() { dc exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec "$@"' sh "$@"; }

for needed in "$from/database.sql" "$from/manifest.csv"; do
    [ -f "$needed" ] || { echo "Not a backup made by docker-backup.sh or backup.ps1: $needed is missing." >&2; exit 1; }
done
[ -d "$from/files" ] || { echo "Not a backup: $from/files is missing." >&2; exit 1; }
if ! tail -n 3 "$from/database.sql" | grep -q 'Dump completed'; then
    echo "This backup is damaged: database.sql does not end the way a finished dump does." >&2
    exit 1
fi

# The backup itself first: restoring from a damaged one would replace good data with bad.
referred=0
problems=0
while IFS=, read -r _kind key sha _bytes; do
    key="${key//\"/}"; sha="${sha//\"/}"
    referred=$((referred + 1))
    path="$from/files/${key:0:2}/$key"
    if [ ! -f "$path" ]; then
        echo "  missing: $key" >&2; problems=$((problems + 1))
    elif [ "$(sha256sum "$path" | cut -d' ' -f1)" != "$sha" ]; then
        echo "  altered (checksum differs): $key" >&2; problems=$((problems + 1))
    fi
done < <(tail -n +2 "$from/manifest.csv")
if [ "$problems" -gt 0 ]; then
    echo "This backup is damaged: $problems stored file(s) are not as recorded in its manifest." >&2
    exit 1
fi
echo "Backup $from is intact: database.sql and $referred stored file(s)."

if [ "$overwrite" != "yes" ]; then
    echo
    echo "Nothing was changed. To restore, run again with --overwrite. That will:"
    echo "  - stop the backend"
    echo "  - DROP the fams database and replace it with the one in the backup"
    echo "  - add the backup's files to the documents volume"
    exit 0
fi

echo "Stopping the backend ..."
dc stop backend
dc up -d --wait mysql

echo "Replacing the database ..."
printf '%s\n' 'DROP DATABASE IF EXISTS fams; CREATE DATABASE fams;' | mysql_root mysql -uroot
# "source" is not used: the dump is streamed in as bytes, so nothing re-encodes it on the way.
if ! mysql_root mysql -uroot --default-character-set=utf8mb4 fams < "$from/database.sql"; then
    echo "Loading the database failed. The database may be incomplete: fix the cause and run the restore again." >&2
    exit 1
fi

echo "Putting the stored files back ..."
tar -C "$from/files" -cf - . \
    | dc run --rm -T --no-deps --entrypoint tar backend -C /data/documents --no-same-owner --skip-old-files -xf -

# Every file the database refers to must now be where the application will look for it, unaltered.
bad=$(tail -n +2 "$from/manifest.csv" | tr -d '"' | awk -F, '{ print $2, $3 }' \
    | dc run --rm -T --no-deps --entrypoint sh backend -c '
        while read -r key sha; do
            p="/data/documents/$(printf %s "$key" | cut -c1-2)/$key"
            if [ ! -f "$p" ]; then echo "missing: $key"
            elif [ "$(sha256sum "$p" | cut -d" " -f1)" != "$sha" ]; then echo "altered (checksum differs): $key"; fi
        done')
if [ -n "$bad" ]; then
    echo "RESTORE INCOMPLETE: stored file(s) in the documents volume are not as the database records them:" >&2
    echo "$bad" | sed 's/^/  /' >&2
    exit 1
fi
echo "Restore complete and verified. Start the stack:"
echo "  docker compose -f docker-compose.production.yml --env-file .env.docker up -d"
