# Docker deployment (college network)

This is the step-by-step runbook for installing FAMS with Docker Compose. The principles (college network only, HTTPS,
the sign-in limits needing the real client address) are in `docs/deployment.md`; this file is how to do it with containers.

```
 browsers on the        :80 / :443              private Docker network "internal" (no route to or from the outside)
 college network  --->  nginx  ---- /api/* ---> backend (Spring Boot :8080) ---> mysql (8.4)
                        (TLS)   \--- the rest-> frontend (Next.js :3000)         backend --> documents volume
```

| Container | Image | Published | Runs as | Persistent data |
|---|---|---|---|---|
| `nginx` | `nginx:1.30-alpine` | **80 and 443** (the only ones) | root master, `nginx` workers; all capabilities dropped but five | none; reads the TLS files from the host |
| `backend` | `fams-backend` (Java 25, built from `backend/Dockerfile`) | nothing | uid 10001 | volume `documents` (issued reports) |
| `frontend` | `fams-frontend` (Node 20, built from `frontend/Dockerfile`) | nothing | `node` (uid 1000) | none |
| `mysql` | `mysql:8.4` | nothing | `mysql` (uid 999) | volume `mysql-data` |

The backend, frontend and application containers have a read-only root file system, no Linux capabilities and
`no-new-privileges`. The `dev` Spring profile is never set. Cookies are `Secure`. The browser and the API share one origin
(Nginx serves both), so no CORS is configured. The application connects to MySQL as an account that can read and write rows
only; a second account (`fams_migrator`) creates tables and triggers at start-up, as `docs/deployment.md` recommends.

> **A copy on the same machine is not a backup.** The `mysql-data` and `documents` volumes live on this server's disk. A
> failed disk, a deleted folder, `docker compose down -v` or `docker volume rm` destroys the database and every issued
> report. Back up to a different disk or machine (section 8), and try a restore.

## What the college must supply or decide

- A **Linux server** with Docker Engine and the Compose plugin (Compose **2.24 or newer**; check with `docker compose version`),
  on the college network, with the clock on network time. Docker Desktop (Windows, macOS) and rootless Docker are fine for a
  trial run but **not for production**: they hide each client's address behind a gateway address, so every user shares one
  sign-in limit (`docs/security.md`).
- A **host name** (for example `appraisal.<college domain>`) that resolves, on the college network, to the server, and a
  **TLS certificate and private key** for exactly that name, from the college's certificate authority.
- A **backup destination** on a different disk or machine, mounted on the server (section 8), and someone who owns running
  and testing it.
- The **time zone** for dates on reports and in the audit trail (`FAMS_TIMEZONE`, default `Asia/Kolkata`). Decide it before the first start.
- Whether the **database may be reached by anything but the application**. The stack publishes no MySQL port. Changing that
  is a decision to make deliberately (`docs/deployment.md`, "Limit who can reach it").
- Whether the private range **172.29.88.0/24** is free on the college network (section 11). If not, pick another.
- The **standard old password** (`FAMS_DEFAULT_PASSWORD`) or whether to keep the built-in one (`docs/deployment.md`).
- A **firewall rule** allowing the staff and faculty networks to ports 80 and 443 of the server, and nobody else.
- Who has **shell access to the server**: anyone who can run `docker` there can read every secret and all the data.

## 1. Prepare the server

Install Docker Engine and the Compose plugin from the college's package source. Then, as the person who will run the stack:

```bash
docker version && docker compose version          # both must answer
git clone <the project repository> /opt/fams       # or unpack the release you were given
cd /opt/fams
```

Everything below is run from this folder. **Always** give the settings file explicitly (`--env-file .env.docker`); Compose
would otherwise read a `.env` in the folder, which on a developer's machine holds the development passwords.

## 2. Create the production settings file

```bash
cp .env.docker.example .env.docker
chmod 600 .env.docker
```

Edit `.env.docker` and set at least:

| Setting | What to put |
|---|---|
| `FAMS_PUBLIC_HOST` | the host name people type, the one on the certificate |
| `FAMS_DB_PASSWORD`, `FAMS_DB_MIGRATION_PASSWORD`, `FAMS_DB_ROOT_PASSWORD` | three different long random passwords: `openssl rand -hex 24` (letters and digits only) |
| `FAMS_BACKUP_DIR_HOST` | the absolute path of the backup folder (section 8); it must exist |
| `FAMS_IMAGE_TAG` | a name for this version, for example `2026.10` |

The file is never committed (`.gitignore`), never copied into an image, and holds the only copy of these passwords: keep it
in the college's password safe as well. **The database passwords are read when the MySQL volume is first created.** Changing them
in the file later does not change them in MySQL (section 12).

## 3. Create the persistent storage

By default Docker creates two **named volumes** the first time the stack starts: `fams-prod_mysql-data` (the database) and
`fams-prod_documents` (issued reports). Nothing else is needed, and the documents volume takes the image's ownership
(uid 10001, mode 0750) by itself. See them with `docker volume ls`.

To keep the data in folders of your choosing instead, create them with the right owner and name them in `.env.docker`:

```bash
sudo install -d -o 10001 -g 10001 -m 0750 /srv/fams/documents     # backend: uid 10001
sudo install -d -o 999   -g 999   -m 0750 /srv/fams/mysql         # MySQL: uid 999
# in .env.docker:
#   FAMS_DOCUMENTS_DATA=/srv/fams/documents
#   FAMS_MYSQL_DATA=/srv/fams/mysql
```

Put the backup folder (section 8) and, if you like, these folders on disks you can see and monitor. Do not make either
folder world-writable: `0750` owned by the container's user is all they need.

## 4. Provide the TLS certificate

Put two files in `deploy/tls/` (or in another folder named by `FAMS_TLS_DIR`):

- `fullchain.pem` - the server certificate followed by any intermediate certificates (PEM)
- `privkey.pem` - its private key (PEM, not password-protected)

```bash
sudo chown root:root deploy/tls/privkey.pem && sudo chmod 600 deploy/tls/privkey.pem
sudo chmod 644 deploy/tls/fullchain.pem
```

The folder is mounted read-only into the Nginx container, which reads the key as root and then serves as an unprivileged
user. The certificate files are not in git and not in any image. Browsers must trust the college's certificate authority,
as for any other internal site. Renew by replacing the two files and running
`docker compose -f docker-compose.production.yml --env-file .env.docker exec nginx nginx -s reload`.

*Only for a trial run*, a throw-away certificate (browsers will warn about it):

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout deploy/tls/privkey.pem -out deploy/tls/fullchain.pem \
  -subj "/CN=appraisal.example.edu" -addext "subjectAltName=DNS:appraisal.example.edu"
```

## 5. Build the images (on a machine with internet access)

The build downloads libraries (Maven Central, npm) and the web fonts once; nothing needs the internet at run time.

```bash
docker compose -f docker-compose.production.yml --env-file .env.docker build
docker compose -f docker-compose.production.yml --env-file .env.docker pull mysql nginx
```

This produces `fams-backend:<tag>` and `fams-frontend:<tag>` (the tag is `FAMS_IMAGE_TAG`). The build needs the settings file
to exist, but not real values: copying `.env.docker.example` and setting `FAMS_IMAGE_TAG` is enough on a build-only machine.
Tests, Checkstyle and SpotBugs are not part of the image build; run them as usual (`scripts\verify.ps1`, CI) before building a
release.

If the server itself can reach the internet, build on it and skip section 6.

## 6. Move the images to an offline server (`docker save` / `docker load`)

On the build machine:

```bash
TAG=2026.10        # the FAMS_IMAGE_TAG you built
docker save -o fams-images-$TAG.tar fams-backend:$TAG fams-frontend:$TAG mysql:8.4 nginx:1.30-alpine
sha256sum fams-images-$TAG.tar > fams-images-$TAG.tar.sha256
```

Copy to the server (USB drive, internal file share): `fams-images-<tag>.tar`, its `.sha256`, and the project folder
(`docker-compose.production.yml`, `docker-compose.http-test.yml`, `deploy/`, `scripts/`, `.env.docker.example`). Not
`.env`, not any certificate or key, and no `.env.docker` from another machine. On the server:

```bash
sha256sum -c fams-images-2026.10.tar.sha256            # must say OK
docker load -i fams-images-2026.10.tar
docker image ls                                         # fams-backend, fams-frontend, mysql, nginx are listed
```

Then create `.env.docker` (section 2) with the same `FAMS_IMAGE_TAG`, and start with `--no-build --pull never` so that
Compose neither tries to build nor to reach a registry (section 7).

## 7. Start, stop, update

Start (or apply a changed setting):

```bash
docker compose -f docker-compose.production.yml --env-file .env.docker up -d --no-build --pull never
docker compose -f docker-compose.production.yml --env-file .env.docker ps          # all four "healthy"
```

MySQL starts first and creates its volume; the backend waits for MySQL to be healthy, runs the database migrations
(`docs/database.md`) and then reports healthy; Nginx starts when the backend and frontend are healthy. The first start takes
a minute or two. If it stays unhealthy, read the logs:

```bash
docker compose -f docker-compose.production.yml --env-file .env.docker logs --tail 100 backend
```

Stop (everything stays in the volumes) and start again:

```bash
docker compose -f docker-compose.production.yml --env-file .env.docker stop
docker compose -f docker-compose.production.yml --env-file .env.docker start
```

> **Never run `docker compose down -v`, `docker volume rm` or `docker system prune --volumes`** on this stack. They delete the
> database and the issued reports. Plain `docker compose ... down` removes only containers and is safe.

Check that it is what it should be:

```bash
curl -sI http://<FAMS_PUBLIC_HOST>/ | head -3            # 301, Location: https://<FAMS_PUBLIC_HOST>/
curl -sI https://<FAMS_PUBLIC_HOST>/api/auth/csrf        # 200, with a Set-Cookie that has the Secure flag
sudo ss -ltnp | grep -E ':(80|443|3000|8080|3306)\b'     # only 80 and 443 are listening
```

**Update to a new version:** take a backup (section 8), build or load the new images under a new `FAMS_IMAGE_TAG`, change the tag in
`.env.docker`, and run the `up -d` command above. The database migrations run by themselves when the backend starts and cannot be
undone, so going back to an older version means restoring the backup made before the update, with the old tag.

## 8. First administrator, backups and restores

### Create the first administrator

On a new installation no one can sign in until an administrator exists. In `.env.docker` set

```
FAMS_BOOTSTRAP_ADMIN_EMAIL=<an address>
FAMS_BOOTSTRAP_ADMIN_PASSWORD=<a password that meets the rules: 10+ characters, a letter and a number, not common>
```

and run the `up -d` command (section 7). The log says `Bootstrap administrator created for ...`:

```bash
docker compose -f docker-compose.production.yml --env-file .env.docker logs backend | grep -i bootstrap
```

Open `https://<FAMS_PUBLIC_HOST>/`, sign in, and choose a new password when asked. Then **delete both lines from `.env.docker`** and run
`up -d` again (it recreates the backend). Continue with the administrator console's checklist (`docs/deployment.md`, "First start"): departments, an open
year, accounts, a second administrator.

### Run a backup

`scripts/docker-backup.sh` makes the same backup as `scripts/backup.ps1` does on a development machine: one folder with
`database.sql`, the stored files, and a manifest, then checks every report file against the SHA-256 the database recorded.
Run it on the Docker host (bash, GNU tar and `sha256sum` are all it needs; MySQL's tools run inside the MySQL container, so nothing
is installed on the host). It reads no passwords from the command line: the dump is made inside the container with its own root
password.

```bash
scripts/docker-backup.sh /srv/fams-backups          # the folder FAMS_BACKUP_DIR_HOST names
```

It can run while people use the system, exits with an error if anything is missing or does not match, and leaves
`latest.json` or `last-failure.json` in that folder, which the administrator console reads to show the age of the last backup.
Schedule it (the user who owns the project folder and can run `docker`), for example nightly:

```
15 2 * * *  /opt/fams/scripts/docker-backup.sh /srv/fams-backups >> /var/log/fams-backup.log 2>&1
```

The folder must be on **another disk or machine**, and must be copied further (tape, another building) on a schedule you can
defend. Removing old backups is up to you (for example `find /srv/fams-backups -maxdepth 1 -name 'fams-backup-*' -mtime +30 -exec rm -r {} +`).
Backups contain every account's password hash and every appraisal: protect the folder like the database.

### Verify a backup and restore

Check a backup without changing anything (it re-verifies every file against its manifest):

```bash
scripts/docker-restore.sh /srv/fams-backups/fams-backup-20261006-021500
```

Restore (this **drops and replaces** the database with the backup's; what was entered since is lost, so take a fresh backup first):

```bash
scripts/docker-restore.sh /srv/fams-backups/fams-backup-20261006-021500 --overwrite
docker compose -f docker-compose.production.yml --env-file .env.docker up -d
```

The script stops the backend, replaces the database, puts the files back (files are only ever added), verifies them again and
leaves the backend stopped until you start it. **Try a restore on a spare machine at least once** before you rely on it: install the stack
there (sections 1-7, without the bootstrap administrator), run the restore, start, sign in, open a report. Record the test in the
administrator console (*System health*, "Record a restore test"), which warns when the last one is old.

## 9. LAN-only HTTP trial option (temporary, not for real users)

To see the stack work before the certificate is ready, a second file replaces the HTTPS Nginx configuration with plain HTTP on
port 80 and turns `FAMS_SECURE_COOKIES` off (a Secure cookie is not sent over HTTP, so sign-in would not work):

```bash
docker compose -f docker-compose.production.yml -f docker-compose.http-test.yml --env-file .env.docker up -d --no-build --pull never
```

Passwords and sessions then cross the network readable by anyone on the same segment. Use it only on a closed test network with no real
accounts or data; do not keep a database that was used this way. To go to production, run the command of section 7 (production file alone);
it recreates `nginx` and `backend`. No certificate files are needed for the trial.

## 10. What is and is not in the images

Images contain the application and its libraries only. Reports, database files, TLS certificates, passwords and bootstrap
credentials are never baked in: they come from the volumes, from `.env.docker` and from the certificate folder at run time.
`.dockerignore` keeps `node_modules`, `target`, logs, local storage, secrets and development tooling out of the build.

Logs go to Docker's `json-file` driver, rotated at 10 MB x 5 per container (`docker compose ... logs`). Sign-ins, refusals and
limit hits are in the backend's log (`docs/security.md`); keep or ship them as your policy requires.

## 11. Changing the private network range

The stack gives Nginx a fixed address on a private network so that the backend can trust `X-Forwarded-For` from that address
alone. The range `172.29.88.0/24` has no route outside the host. If the college already uses it, change these three places in
`docker-compose.production.yml` consistently, then `docker compose ... up -d`:

- `networks.internal.ipam.config[0].subnet` (for example `172.30.99.0/24`)
- `services.nginx.networks.internal.ipv4_address` (`172.30.99.10`)
- `services.backend.environment.FAMS_TRUSTED_PROXIES` (`172[.]30[.]99[.]10`, written as a regular expression)

If the firewall in front of the server rewrites client addresses (NAT, a load balancer), the sign-in limits see that address
instead of the person's; ask for the original address to be passed on and configure Nginx's `real_ip` module accordingly.

## 12. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `required variable ... is missing` | `.env.docker` lacks that setting, or `--env-file .env.docker` was left off |
| `bind source path does not exist` | `FAMS_BACKUP_DIR_HOST` or the TLS folder does not exist; create it |
| Nginx exits, `cannot load certificate` | `fullchain.pem`/`privkey.pem` missing, not matching, or the key is not readable by root |
| Browser: "your connection is not private" | the certificate is for another name, or the college CA is not trusted on that computer |
| `mysql` stays unhealthy on the first start | wait: initialising a new volume takes a minute; then `logs mysql` |
| `Access denied` for `fams` after changing a password in `.env.docker` | MySQL kept the password of the first start. Change it inside MySQL: `docker compose ... exec mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "ALTER USER ..."'` (or restore into a fresh volume) |
| Sign-in works but everybody shares one limit | the client address is not reaching the backend: check you are not on Docker Desktop or rootless Docker, and `logs backend` for the address in "signed in" lines |
| `502 Bad Gateway` | the backend or frontend is unhealthy or restarting: `ps`, then `logs` |
| Backend: `cannot be written to` | a bind-mounted documents folder not owned by uid 10001 (section 3) |

## 13. What was tested

The stack was built and run end to end on a development machine (Docker Desktop, so client addresses were not the real ones):
all four containers healthy; the 25 database migrations applied by the migration account and the application running as the
restricted account (`TRUNCATE` and `DROP` refused); only ports 80 and 443 published; HTTP redirected to HTTPS; a request for the
bare IP address refused; cookies `Secure`, `HttpOnly`, `SameSite=Lax`; a forged `X-Forwarded-For` ignored; the read-only root file
system with the documents volume writable by uid 10001 only; a backup, a refused damaged backup (wrong checksum, missing file,
truncated dump), a restore and a restart on the restored data; the plain-HTTP trial override and the return to production.
The one thing a Linux server must still confirm: that the sign-in log shows each person's own address rather than a gateway's
(section 12, "everybody shares one limit").
