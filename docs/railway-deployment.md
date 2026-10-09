# Railway deployment

FAMS can run on Railway (a public cloud). **This is a different risk from the college-network install** (`docs/deployment.md`,
`docs/docker-deployment.md`): the sign-in page is reachable from the whole internet, and the appraisals of faculty are
stored on Railway's servers. Everything below assumes the college accepts that. The Docker Compose stack is *not* used on
Railway; it is built from the same Dockerfiles, one service each.

```
 browsers --HTTPS--> Railway edge --> fams-web (Nginx, public)  --/api/*--> fams-backend (Spring Boot)  --> fams-db (MySQL 8.4)
                                                              \--- else --> fams-frontend (Next.js)               volume: /var/lib/mysql
                                      private network (*.railway.internal)      volume: /data/documents (reports)
```

| Service | Built from | Public | Volume | Notes |
|---|---|---|---|---|
| `fams-db` | `deploy/railway/mysql.Dockerfile` (context `deploy/`) | no | `/var/lib/mysql` | MySQL **8.4** (Railway's own template is 9.x). Creates the restricted `fams` account and the `fams_migrator` account |
| `fams-backend` | `backend/Dockerfile` (context `backend/`) | no | `/data/documents` | runs as root (`RAILWAY_RUN_UID=0`, below) |
| `fams-frontend` | `frontend/Dockerfile` (context `frontend/`) | **no** | none | |
| `fams-web` | `deploy/railway/nginx.Dockerfile` (context `deploy/`) | **yes** | none | the only service with a domain |

## Variables

Reference variables (`${{service.NAME}}`) keep secrets out of files and out of chat.

- `fams-db`: `MYSQL_DATABASE=fams`, `MYSQL_USER=fams_migrator`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `MYSQL_ROOT_HOST=localhost`, `FAMS_APP_DB_USER=fams`, `FAMS_APP_DB_PASSWORD` (three different random passwords), `TZ`, `RAILWAY_DOCKERFILE_PATH=railway/mysql.Dockerfile`
- `fams-backend`: `FAMS_DB_URL=jdbc:mysql://${{fams-db.RAILWAY_PRIVATE_DOMAIN}}:3306/fams?sslMode=REQUIRED`, `FAMS_DB_USER=fams`, `FAMS_DB_PASSWORD=${{fams-db.FAMS_APP_DB_PASSWORD}}`, `SPRING_FLYWAY_USER=fams_migrator`, `SPRING_FLYWAY_PASSWORD=${{fams-db.MYSQL_PASSWORD}}`, `FAMS_BIND_ADDRESS=::`, `FAMS_FORWARD_HEADERS=native`, `FAMS_TRUSTED_PROXIES` (private ranges, below), `FAMS_SECURE_COOKIES=true`, `FAMS_STORAGE_DIR=/data/documents`, `RAILWAY_RUN_UID=0`, `TZ`
- `fams-frontend`: `BACKEND_URL=http://${{fams-backend.RAILWAY_PRIVATE_DOMAIN}}:8080`, `HOSTNAME=::`, `PORT=3000`, `NEXT_TELEMETRY_DISABLED=1`, `TZ`
- `fams-web`: `RAILWAY_DOCKERFILE_PATH=railway/nginx.Dockerfile`, `FAMS_BACKEND_URL=http://${{fams-backend.RAILWAY_PRIVATE_DOMAIN}}:8080`, `FAMS_FRONTEND_URL=http://${{fams-frontend.RAILWAY_PRIVATE_DOMAIN}}:3000`, `PORT=8080`, `TZ`

## Deploying (CLI)

```bash
railway login
railway link -p <project id> -e production
railway up deploy   --path-as-root -s fams-db       # MySQL image, volume mounted at /var/lib/mysql first
railway up backend  --path-as-root -s fams-backend
railway up frontend --path-as-root -s fams-frontend
railway up deploy   --path-as-root -s fams-web      # then: railway domain -s fams-web
```

Create the volumes with `railway volume -s <service id> add -m <path>` before the first deploy of that service. In Git Bash on Windows set
`MSYS_NO_PATHCONV=1`, or `/var/lib/mysql` is rewritten into a Windows path. Deploy `fams-web` **after** `fams-frontend` and
`fams-backend` exist and have deployed once, or their private addresses are empty in its variables (Nginx then logs
`no host in upstream`); `railway service redeploy -s fams-web` fixes it.

## Things Railway does that you must undo or know

- **Railway may give a service a public domain by itself.** After the first deploy `fams-frontend` had one. Reaching the frontend
  directly skips Nginx, so the sign-in limits then see Railway's edge addresses instead of the person's. Only `fams-web` may have a
  domain: `railway domain delete <domain> -s fams-frontend -y`. Check with `railway status --json` after every new service.
- **The client address.** Railway's edge puts the client in `X-Real-IP`; the backend reads `X-Forwarded-For`. Nginx (`deploy/nginx/fams-railway.conf.template`) copies the first into the second and drops whatever the client sent, so a forged header is
  ignored (tested: the backend logged the real address). The edge itself connects from `100.64.0.0/10`, and the services' private
  addresses are in the private ranges `FAMS_TRUSTED_PROXIES` lists; the backend is not reachable from outside.
- **Volumes are root-owned**, so the unprivileged backend user cannot write to its reports volume. The documented fix, used here, is
  `RAILWAY_RUN_UID=0`: the backend (only) runs as root inside its container. A later improvement is an entrypoint that fixes the volume's
  owner and then drops to uid 10001.
- **No `--mount=type=cache` in Dockerfiles**: Railway's builder requires a cache id and refuses the build without one.
- **Volumes and replicas:** a service with a volume cannot have replicas. Plan limits apply (the Free plan allowed 4 services and small volumes).

## First administrator

`FAMS_BOOTSTRAP_ADMIN_EMAIL` / `FAMS_BOOTSTRAP_ADMIN_PASSWORD` on `fams-backend` create the first administrator once, while none exists.
Read the password in the Railway dashboard (service, Variables), sign in, choose a new password when asked, then **delete both variables**.

## Backups and what is not covered

`scripts/docker-backup.sh` is for the Compose stack and does not apply here. Use Railway's volume backups for `fams-db` and `fams-backend`
(Backups tab) and take a `mysqldump` through `railway connect`/the private network as your own copy; a copy on Railway alone is not an
off-platform backup. The administrator console will say backups are not watched (no `FAMS_BACKUP_DIR`).
Set `FAMS_DEFAULT_PASSWORD` to your own value: the built-in standard password is in the public source and the site is on the internet.
