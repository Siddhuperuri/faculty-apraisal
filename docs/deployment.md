# Deployment: the college network only

FAMS is built to run **only inside the college network**. It is not meant to be reachable from the public internet, and
nothing in it needs the internet once it is running.

## What that means for the build

- **No outbound access at runtime.** No CDN, hosted font, analytics, e-mail service or external login is used while the
  system runs. The fonts are downloaded once, during `npm run build`, and served from the application itself.
- **Build on a connected machine, copy the result in.** `mvn` and `npm` need the internet to fetch libraries (and Google
  Fonts) the first time. Build there, then copy the artifacts to the server:
  - backend: `cd backend && mvn -DskipTests package` produces `target/fams-backend-*.jar` (Java 25 to run it);
  - frontend: `cd frontend && npm ci && npm run build`, then copy `frontend/` including `.next/`, `public/`,
    `package.json`, `next.config.ts` and `node_modules/` (Node 20+ to run it with `npm start`). Set
    `NEXT_TELEMETRY_DISABLED=1` so Next does not try to phone home.
- **MySQL 8.x** on the college network (or the same machine). The schema is created by Flyway when the backend first starts.

## Shape of an installation

```
 staff and faculty browsers  -->  reverse proxy (TLS)  -->  Next.js :3000  (pages)
   (college network only)                              \-->  Spring Boot :8080  (/api/*)  -->  MySQL
                                                                      \--> document storage directory
```

Put one hostname in front (for example `appraisal.<college domain>`). Route `/api/*` to the backend and everything else to
Next. The frontend also proxies `/api/*` itself (`BACKEND_URL`, read **when `npm run build` runs**), so either set
`BACKEND_URL` for the build or let the reverse proxy do the routing and ignore it.

**Only the reverse proxy faces the network.** The backend listens on this machine only (`FAMS_BIND_ADDRESS`, default
`127.0.0.1`); start Next the same way (`npm start -- -H 127.0.0.1`). Nothing can then reach either of them except through
the proxy, which is what makes the address the proxy reports trustworthy. Pages are rendered for each request (the
Content-Security-Policy carries a per-request nonce), so do not put a page cache in front of Next.

## Installing with Docker

`docker-compose.production.yml` is a ready-made version of the shape above: MySQL 8.4, the backend, the frontend and an
Nginx reverse proxy on a private Docker network. **Only Nginx publishes ports (80 and 443)**; it redirects HTTP to HTTPS, serves
the pages, sends `/api/*` straight to the backend and sets `X-Forwarded-For`, `X-Forwarded-Proto` and `Host`. The backend runs
with `FAMS_FORWARD_HEADERS=native` and trusts those headers from Nginx's fixed address on that network only
(`FAMS_TRUSTED_PROXIES`), with Secure cookies, no `dev` profile and no CORS. The application connects to MySQL as an account
that can read and write rows only, while a separate account migrates the schema (the "Database account" advice below).
Issued reports and the database live in Docker volumes; the TLS certificate and key are mounted from the host and are not in an image.

```bash
cp .env.docker.example .env.docker && chmod 600 .env.docker        # fill in the passwords, host name, backup folder
docker compose -f docker-compose.production.yml --env-file .env.docker build     # on a machine with internet access
docker compose -f docker-compose.production.yml --env-file .env.docker up -d
scripts/docker-backup.sh /srv/fams-backups                          # scripts/docker-restore.sh <folder> [--overwrite]
```

The images can be built on a connected machine and moved to an offline server with `docker save` / `docker load`. The step-by-step
runbook, with the first administrator, backups, restores and what the college has to supply, is `docs/docker-deployment.md`.
**Docker volumes on the same machine are not an off-site backup**: if the server's disk fails or the volumes are removed, the
database and the issued reports go with them. Use `scripts/docker-backup.sh` to copy them to another disk or machine.
`docker-compose.http-test.yml` is a temporary plain-HTTP option for a closed trial run only.

## Settings (environment variables of the backend)

| Variable | Meaning |
|---|---|
| `FAMS_DB_URL`, `FAMS_DB_USER`, `FAMS_DB_PASSWORD` | Database connection. The URL and the password have no default: the application does not start without them |
| `FAMS_SECURE_COOKIES` | Default `true`: cookies are sent over HTTPS only. Sign-in does not work over plain HTTP unless this is set to `false`, which exposes passwords and sessions to the network; do that only on a closed test set-up |
| `FAMS_FORWARD_HEADERS` | `native` when behind the reverse proxy, so the real client address is used (it is written to the log and the audit trail). `framework` is refused at start-up: it believes an address the client can forge |
| `FAMS_TRUSTED_PROXIES` | Which addresses are the reverse proxy, as a regular expression. Default: this machine. Only needed if the proxy runs elsewhere, for example `10[.]1[.]2[.]3` |
| `FAMS_BIND_ADDRESS` | The address the backend listens on. Default `127.0.0.1`. Set it (for example `0.0.0.0`) only if the proxy runs on another machine, and firewall port 8080 to that machine |
| `FAMS_STORAGE_DIR` | **Required.** The absolute path of the directory where issued official reports are kept. It must be on storage that outlives the application (a data disk or a shared volume, not a temporary or working directory): the backend does not start without a directory it can write to. Private, outside any web root, writable by the service account only. With several instances, the same shared volume for all |
| `FAMS_BOOTSTRAP_ADMIN_EMAIL`, `FAMS_BOOTSTRAP_ADMIN_PASSWORD` | Creates the first administrator, only while none exists. Remove both afterwards |
| `FAMS_RESET_ALL_PASSWORDS` | A token. When the application starts with a token it has not seen before, every account (administrators included) is set back to the standard password and must choose a new one at its next sign-in; a token runs once, so a restart does nothing, and to repeat it give a new one. For when no administrator can use the console's bulk reset. **Unset it afterwards** |
| `FAMS_CORS_ORIGIN` | Default empty: no other origin may call the API from a browser. Name one origin only if the pages are served from a different origin than the API (they normally are not) |

Never enable the `dev` Spring profile on the server: it creates demo accounts with a shared password and turns off the
Secure flag on cookies. Without a profile the application starts with its production defaults.

## Being on the college network is not the same as being trusted

Students and visitors are on the same network as staff, so the usual protections still matter:

- **Use HTTPS.** On plain HTTP anyone on the same network segment can read passwords as they are typed. Use a certificate
  from the college's own certificate authority (or the college's wildcard certificate) at the reverse proxy.
- **Limit who can reach it.** At the firewall or reverse proxy, allow the staff and faculty subnets or VLANs only, and keep
  the database port closed to everything except the application server.
- **The real client address is still worth getting right** behind a proxy: it is what the log and the audit trail record for a sign-in.
- **Send `Strict-Transport-Security` from the reverse proxy** once HTTPS works, so browsers stop trying plain HTTP.
- **Database account:** the application user needs normal read/write rights, not `DROP` or `TRUNCATE` (those would bypass
  the append-only history tables). Run migrations with a separate account: set `SPRING_FLYWAY_USER` and
  `SPRING_FLYWAY_PASSWORD` to an account that may create tables and triggers, and give the `FAMS_DB_USER` account only
  `SELECT, INSERT, UPDATE, DELETE` on the `fams` database. Binary logging needs `log_bin_trust_function_creators=1`
  (or `SUPER` for the migration user) because the schema uses triggers.
- **Database connection:** if MySQL is on another machine, encrypt the connection (`?sslMode=VERIFY_CA` in `FAMS_DB_URL`,
  without `allowPublicKeyRetrieval`). The example settings are for a database on the same machine.

## Operations

- **Backups:** `scripts\backup.ps1 -To <folder>` writes one folder holding the database (`database.sql`), the stored
  files (`files\`) and a manifest, then checks that every file the database refers to is in the copy with the SHA-256
  the database recorded; it exits with an error if not. It reads the backend's own settings (`FAMS_DB_URL`,
  `FAMS_DB_USER`, `FAMS_DB_PASSWORD`, `FAMS_STORAGE_DIR`) from the environment or `.env`, needs the MySQL client tools
  (`mysqldump`, `mysql`) and can run while the application is in use. Schedule it (for example nightly) and keep the
  folders somewhere other than the server.
- **Recovery:** stop the backend, run `scripts\restore.ps1 -From <backup folder> -Overwrite`, start the backend. The
  script first checks the backup itself, then replaces the database, puts the files back into `FAMS_STORAGE_DIR` and
  verifies them again. Without `-Overwrite` it only checks the backup and says what it would do. It needs a database
  account that may create tables and triggers (`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` when set). Try a restore
  on a spare machine at least once.
- **Sign-in limits** are kept in the database, so they need no set-up of their own, are shared by every instance and
  survive restarts. The application account's ordinary `SELECT, INSERT, UPDATE, DELETE` rights cover them.
- **Clock:** keep the server on network time (NTP). Audit entries, sessions and report dates depend on it.
- **Logs:** the backend writes to standard output with a request ID per request; nothing secret is logged. Keep them:
  sign-ins, failed sign-ins, limits being hit, sessions ended by the server and refused requests are all there.
  A line saying a limit is "being refused" means someone is guessing passwords.
- **First start:** set the bootstrap variables, start the backend, sign in, change the password when asked, create the real
  accounts under *Accounts*, then remove the bootstrap variables. The administrator console's checks say what is still
  missing (an open academic year, a scoring policy for every cadre, a Head of the Department for every department with
  faculty, a Principal or Director Technical, a second administrator). Both levels of the approval chain need an active account, or appraisals
  stop there.

## Not provided

Single sign-on with the college directory (LDAP / Active Directory), e-mailed passwords or reset links, antivirus scanning
of uploads, and high availability. Each is a decision for the college; see `docs/requirements.md` and `docs/security.md`.
