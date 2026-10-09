# Faculty Appraisal Management System (FAMS)

Digital version of the Sri Vasavi Engineering College (Autonomous) *Faculty Self-Appraisal & Assessment Report*:
faculty complete the appraisal section by section and submit it to their Head of the Department, who approves and forwards
it to the Principal for the final decision, and every step is audited.

The official six-page form (Part A, Part B items 1-11, declaration, HoD and Principal boxes, Annexure A) is the source of
truth; headings, column names and the cadre-wise maximum marks follow it exactly.

## What exists today

| Area | State |
|---|---|
| Faculty UI: sign in, dashboard, all form pages 01-11, autosave, add / edit / duplicate / delete rows, totals the form asks for | Done, tried in a browser |
| Declaration and submit, read-only from submission onward (nothing is returned for correction) | Done |
| The approval chain HoD -> Principal: review queue, read-only view of the whole appraisal, begin / comment / approve at each level (the HoD's approval forwards it; the Principal's is final). No return step | Done |
| Score sheet: cadre maximum marks (Annexure A), the scoring components each maximum is made of for that cadre (B1 to B5, from the college's cadre-wise document) and the faculty member's own self-scores, validated 0 to maximum, autosaved | Done |
| A console for each level: Faculty (journey, scores, report, history), HoD (department progress, full roster), Principal (college-wide by department, awaiting list), Administrator (set-up checks, accounts, appraisal counts, activity) | Done |
| Part A identity block (name, ID, cadre, department, e-mail from the profile) with the editable details beneath it | Done, tried in a browser |
| PDF report mirroring the college's form: DRAFT-watermarked until approval, then generated once, stored and served unchanged | Done, checked page by page |
| Accounts: administrators create faculty / HoD / Principal / administrator accounts one at a time or from a CSV file, all starting with the standard password; forced change at first sign-in; change password; disable; reset | Done, tried in a browser |
| Administration: departments, academic years, scoring policy (new versions, each cadre totals 100), audit trail | Done, tried in a browser |
| Backend: schema, security, workflow, 20 form sections, self-scores and scoring components, report, accounts, admin, audit trail | Done, 291 tests |
| Completeness checks before submit | A minimum is enforced (Part A basics, one course, all self-scores); the college has not said what else is mandatory |
| Self-service password reset, e-mail delivery, SSO | **Not built** (see `docs/security.md`) |

## Run it locally

**Quick start (Windows, after the one-time setup below):**

```
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
```

This starts the project's MySQL (port 3307), the backend (8080) and the frontend (3000), skips anything already running,
waits until each answers, and prints the demo sign-ins. Open http://localhost:3000. Stop with
`scripts\stop-all.ps1` (add `-Database` to stop MySQL too). Logs are in `tools\`.

The steps below are what the script does, for doing it by hand or on another machine.

Prerequisites: Java 25, Maven 3.9+, Node 20+, MySQL 8.x (8.4 in development) or Docker.

1. **Database.** `docker compose up -d` (copy `.env.example` to `.env` and set the passwords first), or use any MySQL 8
   server with a `fams` database and user. Binary logging is on by default in MySQL 8 and the schema creates triggers,
   so the server needs `log_bin_trust_function_creators=1` (the compose file sets it) or the migrating user needs `SUPER`.
2. **Configuration.** Copy `.env.example` to `.env` (git-ignored). The backend reads `FAMS_DB_URL`, `FAMS_DB_USER`,
   `FAMS_DB_PASSWORD` (the URL and the password are required, no default), `FAMS_SECURE_COOKIES` (cookies are HTTPS-only
   unless this is `false`; the `dev` profile turns it off for http://localhost), `FAMS_CORS_ORIGIN` (off by default), `FAMS_FORWARD_HEADERS`
   (`native` behind a reverse proxy), `FAMS_TRUSTED_PROXIES`, `FAMS_BIND_ADDRESS` (the backend listens on this machine
   only by default), `FAMS_STORAGE_DIR` (required outside the `dev` profile: the absolute path of a directory that is kept
   and backed up, where issued reports live; `scripts\backup.ps1` copies it with the database).
3. **Backend with demo accounts.** Run `scripts/dev-backend.ps1`. With the
   `dev` profile it creates an administrator (`kavitha.reddy@dev.local`), the Principal (`venkat.rao@dev.local`), the Head of the CSE department (`padmaja.sharma@dev.local`) and one faculty member of each cadre in CSE (`anil.kumar@dev.local` Lecturer, `priya.nair@dev.local` Assistant Professor, `rahul.verma@dev.local` Senior Assistant Professor, `lakshmi.iyer@dev.local` Associate Professor, `srinivas.murthy@dev.local` Professor) (all using the standard password, `Srivasavi@123`, unless `FAMS_DEFAULT_PASSWORD` says otherwise). **Never enable the dev profile in production.** Flyway creates
   the schema and seeds departments, cadres, the 2025-26 academic year and the cadre scoring policy on first start.
   **First administrator on a real installation:** set `FAMS_BOOTSTRAP_ADMIN_EMAIL` and `FAMS_BOOTSTRAP_ADMIN_PASSWORD`
   once (used only while no administrator exists; the password must change at first sign-in), sign in, create the real
   accounts under *Accounts*, then remove both variables.
4. **Frontend.** From `frontend/`: `npm install`, then `npm run dev`, and open http://localhost:3000. The browser only
   talks to this origin; Next proxies `/api/*` to the backend (`BACKEND_URL`, default http://127.0.0.1:8080), so cookies
   and CSRF just work and no CORS is involved.

## Tests

- **Backend** (`backend/`, `mvn verify`): 291 tests, plus style and static analysis. Integration tests need a **separate** database that they wipe:
  create `fams_test`, grant the app user access, set `FAMS_TEST_DB_PASSWORD` (and `FAMS_TEST_DB_URL` if not
  `127.0.0.1:3307`); the variables come from `.env`, so load it into the shell first (as `scripts/dev-backend.ps1` does).
  Covers the state machine, auth, workflow and visibility rules, concurrency, database constraints, real-HTTP cookie and
  CSRF behaviour, every section's validation and round trip, the PDF report, password
  rules and forced change, sessions ending when an account is disabled / re-roled / reset, every admin endpoint against
  every role, policy versioning, the audit view, and the frontend contract.
- **Frontend** (`frontend/`, `npm test`): 49 tests of validation, formatting, password hints, labels and the approval chain, plus a contract test that checks
  every section, column and field the screens refer to against `src/lib/__fixtures__/meta.json`. The backend's
  `FrontendContractTest` fails if that snapshot stops matching the server. After an intentional change to
  `Sections.java`, regenerate it: `mvn test -Dtest=FrontendContractTest -Dfams.updateFixtures=true`.
- `npm run typecheck`, `npm run lint` and `npm run build` are clean.

## Checks

One command runs everything a change must pass, and stops at the first failure:

```
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1
```

- **Backend** (`mvn verify`): compile with every warning as an error, all tests, Checkstyle
  (`backend/config/checkstyle.xml`), SpotBugs with Find Security Bugs, then the runtime libraries against the OSV
  vulnerability database (`scripts/audit-dependencies.mjs`).
- **Frontend** (`npm run check`, then `npm audit --omit=dev`): type check, ESLint with warnings as failures, unit tests,
  production build.

`.github/workflows/ci.yml` runs the same checks on every push and pull request. `-Offline` skips the two that need the
internet. `docs/architecture.md` says what each check fails on.

## How the screens are built

Form fields, limits and allowed values are defined once, on the server (`section/Sections.java`), and published at
`GET /api/sections/meta`. The browser draws every section's form from that, so it can never disagree with the server.
The frontend keeps only presentation: page grouping, the form's headings and which columns each table shows
(`frontend/src/lib/formStructure.ts`). Adding a field is one line in `Sections.java` plus a migration.

## API (implemented)

| Method & path | Who | Purpose |
|---|---|---|
| `GET /api/auth/csrf`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | see code | Session authentication; `me` includes `mustChangePassword` |
| `POST /api/auth/change-password` | signed in | `{"currentPassword","newPassword"}`; checks the rules, signs out the user's other sessions. The only call besides `me`, `csrf` and `logout` allowed while a password change is pending (everything else is 403 `PASSWORD_CHANGE_REQUIRED`) |
| `GET /api/appraisals` | any reviewer or faculty | The appraisals you may open (own / department's submitted / Principal's) |
| `POST /api/appraisals` | faculty | Start this year's appraisal (snapshots cadre marks, prefills Part A from the profile) |
| `GET /api/appraisals/{id}` | owner, assigned HoD, Principal (after HoD approval) | Status, scores, declaration, review history |
| `POST /api/appraisals/{id}/submit` | owner | Body `{"declarationAccepted": true}`; records the declaration (with the server date) and submits |
| `POST /api/appraisals/{id}/review/start` / `approve` | HoD, Principal (each at their own stage) | Begin review; approve with an optional `{"comment"}` (the HoD's approval forwards to the Principal, the Principal's is final). The server picks the step from the caller's role. There is no return |
| `GET` / `POST /api/appraisals/{id}/messages`, `PUT .../messages/{messageId}`, `POST .../messages/read` | owner and assigned HoD (`POST` and `PUT` the HoD only, `PUT` only on their own message; `read` the owner only) | Messages from the HoD to the faculty member. The HoD may send one (`{"message"}`, max 2000 characters), and edit their own, only while the appraisal is `HOD_REVIEW`; editing marks it edited and unread again and keeps the replaced wording (returned as `earlier` to HoDs only); nothing is deleted. While a message exists and the appraisal is `HOD_REVIEW`, the appraisal list, `GET /api/appraisals/{id}` and the HoD console's roster return `queryRaised: true`. Opening them marks them seen. Nobody else can read them |
| `PUT /api/appraisals/{id}/scores` | owner while editable | Body `{"scores": {"TEACHING_LEARNING": 26.5, "OUTREACH": null}}`: sets or clears self-scores for the criteria sent; others untouched; all-or-nothing. Rows come back in the form's order with their labels, the cadre's maximum and its scoring components |
| `GET /api/appraisals/{id}/report.pdf` | anyone who can see the appraisal | The report. `X-Report-Kind: DRAFT` (watermarked) until final approval, then `OFFICIAL`, generated once and unchanged |
| `GET /api/hod/console?academicYearId=` | HoD | Departments, stage counts and the faculty roster; a draft shows as "not yet submitted" |
| `GET /api/principal/console` (`?academicYearId=`) | Principal | Counts per department and in total, and the ten longest-waiting appraisals; nothing named before the HoD has approved |
| `GET /api/admin/overview` | admin | Account counts, set-up checks, appraisal counts by stage (no names), the latest audit entries |
| `GET /api/admin/reference` | admin | Departments, cadres, academic years, the nine criteria |
| `GET` / `POST /api/admin/users`, `GET` / `PUT /api/admin/users/{id}`, `POST /api/admin/users/{id}/reset-password` | admin | Accounts. Create and reset return the old password as `temporaryPassword`; `POST /api/admin/users/import` creates many accounts from a CSV file, all or none; a role never changes; an admin cannot disable or reset themselves; `PUT` changes only the fields it sends |
| `POST /api/admin/departments`, `PUT /api/admin/departments/{id}` | admin | Add; rename / close (the code never changes) |
| `POST /api/admin/academic-years`, `PUT /api/admin/academic-years/{id}` | admin | Open a year (copies each cadre's latest policy in); close / reopen |
| `GET /api/admin/policies?academicYearId=`, `POST /api/admin/policies` | admin | Every policy version; publish the next version (nine whole numbers adding up to 100) |
| `GET /api/admin/audit?page=&size=&action=&entityType=&entityId=` | admin | The audit trail, newest first, at most 100 per page; appraisal entries carry no file names or content |
| `DELETE /api/admin/audit?action=&entityType=&entityId=`, `DELETE /api/admin/audit/{id}` | admin | Deletes every matching entry (all of them when no filter is given) or one entry; records an `AUDIT_DELETED` entry saying who and how many |
| `GET /api/sections/meta` | signed in | Field definitions for all sections |
| `GET /api/appraisals/{id}/sections` | anyone who can see the appraisal | Saved-record count per section |
| `GET` / `PUT /api/appraisals/{id}/sections/{key}` | read: viewers; save: owner while editable | One section's records (+ computed summary); save the complete list |

Section keys, in form order: `general-information`, `teaching-courses`, `mentoring-summary`, `student-achievements`,
`student-projects`, `fdps`, `certifications`, `administrative-roles`, `events`, `journal-publications`,
`conference-papers`, `research-metrics`, `research-scholars`, `phd-progress`, `funded-projects`, `patents-ipr`, `books`,
`outreach`, `memberships-awards`, `other-contributions`. Four hold at most one record (`general-information`,
`mentoring-summary`, `phd-progress`, `other-contributions`).

**Saving a section** sends its complete list. A record with an `id` is updated (only if something changed), one without is
inserted, records missing from the list are deleted. Everything is validated first, so a rejected save changes nothing;
errors come back as `fieldErrors` keyed like `records[0].passPercentage`. State-changing requests must send the token from
`/api/auth/csrf` in `X-XSRF-TOKEN` (the frontend does this). Errors are always `{"message": ...}`.

## Where it runs

Inside the college network only. See `docs/deployment.md` for the shape of an installation, the settings, TLS and the
sign-in rate limit behind a proxy.

### Docker deployment (production)

`docker-compose.production.yml` runs the whole system: MySQL 8.4, the Spring Boot backend, the Next.js frontend and an Nginx
reverse proxy, which is the only container that publishes ports (80 and 443). It is separate from `docker-compose.yml`,
which is the developer's MySQL and is left alone. Settings go in `.env.docker` (copy `.env.docker.example`; never commit it), the
TLS certificate and key are mounted from the host (`deploy/tls/`), and the database and the issued reports live in Docker volumes.

```bash
cp .env.docker.example .env.docker && chmod 600 .env.docker           # fill it in
docker compose -f docker-compose.production.yml --env-file .env.docker build
docker compose -f docker-compose.production.yml --env-file .env.docker up -d
scripts/docker-backup.sh /srv/fams-backups                              # backup, verified
```

A copy on the same machine is **not** a backup: Docker volumes share the server's disk, so back up to another disk or
machine. Full steps (settings, storage, TLS, building, moving images to an offline server with `docker save` / `docker load`,
the first administrator, backups and restores, a temporary HTTP-only trial) are in `docs/docker-deployment.md`.

## Docs

- `docs/README.md` - index of all documentation, with who should read what
- `docs/user-guide.md` - using the system, by role (faculty, HoD, Principal, administrator)
- `docs/developer-guide.md` - set-up, everyday commands, recipes for common changes, troubleshooting
- `docs/database.md` - every table, constraint and trigger, and the migration history
- `docs/requirements.md` - what the official form says, assumptions made, questions still open for the stakeholder
- `docs/workflow.md` - appraisal states and who may do what
- `docs/scoring.md` - how maximum marks are stored and versioned
- `docs/security.md` - implemented controls and known gaps
- `docs/deployment.md` - installing it inside the college network
- `docs/docker-deployment.md` - the same, with Docker Compose: the step-by-step runbook
- `docs/architecture.md` - how the code is organised, where each kind of rule lives, what a change must pass
- `docs/decisions.md` - architecture decisions

## Layout

```
backend/    Spring Boot 3.5 / Java 25 modular monolith (auth, admin, console, appraisal, section, scoring, report, audit, common, dev)
frontend/   Next.js 16 / React 19 / Tailwind 4 (src/app routes, src/components, src/lib)
scripts/    start-all.ps1, stop-all.ps1, dev-backend.ps1 (run it), verify.ps1 (check it), backup.ps1, restore.ps1
            (database and stored files together), docker-backup.sh, docker-restore.sh (the same for the Docker deployment),
            audit-dependencies.mjs
docs/       requirements, workflow, scoring, security, architecture, decisions, deployment
.github/    CI: the same checks as scripts/verify.ps1
docker-compose.yml   development MySQL
docker-compose.production.yml   production stack: MySQL, backend, frontend, Nginx (docker-compose.http-test.yml: trial run over plain HTTP)
deploy/     Nginx configuration, MySQL first-start script, TLS folder (certificates are never committed)
.env.docker.example  settings template for the Docker deployment (copy to .env.docker)
```
