#!/bin/bash
# Runs once, when the database volume is first created (the MySQL image runs every script in docker-entrypoint-initdb.d).
#
# The image creates MYSQL_USER (fams_migrator) with every right on the fams database: Flyway uses it to create tables and
# triggers. This script adds the account the application itself runs as: it may read and write rows and nothing else.
# It cannot DROP or TRUNCATE a table, which would get round the append-only history triggers (docs/security.md, and the
# "Database account" paragraph of docs/deployment.md).
#
# The password is a variable of the container (FAMS_APP_DB_PASSWORD), never written into a file or an image.
# This file does not need to be executable: the image runs it either way.
(
    set -eu
    user="${FAMS_APP_DB_USER:-fams}"
    password="${FAMS_APP_DB_PASSWORD:?FAMS_APP_DB_PASSWORD is not set}"
    case "$user" in
        *[!A-Za-z0-9_]*) echo "FAMS_APP_DB_USER may contain letters, digits and underscores only." >&2; exit 1 ;;
    esac
    # Quote the password for an SQL string: backslash and single quote are the only characters that need it.
    quoted=$(printf '%s' "$password" | sed -e 's/\\/\\\\/g' -e "s/'/\\\\'/g")
    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE USER '${user}'@'%' IDENTIFIED BY '${quoted}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${MYSQL_DATABASE}\`.* TO '${user}'@'%';
SQL
    echo "Created the application account '${user}' with SELECT, INSERT, UPDATE and DELETE on ${MYSQL_DATABASE}."
) || { echo "Creating the application database account failed. Remove the (new, empty) database volume and start again." >&2; false; }
