# MySQL 8.4 for Railway (the project is built and tested on 8.4; Railway's own MySQL template is 9.x).
# Build context: deploy/   (railway up deploy --path-as-root, with RAILWAY_DOCKERFILE_PATH=railway/mysql.Dockerfile).
# Mount the service's volume at /var/lib/mysql. See docs/railway-deployment.md.
FROM mysql:8.4

# Creates the application's restricted account (read and write rows only) when the volume is first used; the image's own
# MYSQL_USER (fams_migrator) migrates the schema. Same script as the Docker Compose stack.
COPY mysql/init/ /docker-entrypoint-initdb.d/

# A new Railway volume holds a lost+found folder, and MySQL refuses to initialise a directory that is not empty, so the
# data lives one level down. The trust flag lets the migration account create the append-only audit triggers.
CMD ["mysqld", "--datadir=/var/lib/mysql/data", "--log-bin-trust-function-creators=1"]
