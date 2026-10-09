# Developer guide

Everything you need to run, change and check FAMS. [architecture.md](architecture.md) explains how the code is organised
and why; this file is the how-to.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 25 | Compiles with `-Xlint:all` and warnings as errors |
| Maven | 3.9+ | A copy is vendored in `tools/apache-maven-*` |
| Node.js | 20+ | Frontend: Next 16, React 19, Tailwind 4, TypeScript 5, Vitest |
| MySQL | 8.x | 8.4 in development; or Docker (`docker-compose.yml`) |
| PowerShell | Windows 5.1+ | The helper scripts in `scripts/` are PowerShell |

## First-time set-up

1. **Settings.** Copy `.env.example` to `.env` (git-ignored) and fill in the passwords. The demo accounts and every new
   account start with the standard password (`Srivasavi@123`; `FAMS_DEFAULT_PASSWORD` overrides it). Never commit `.env`.
2. **Database.** `docker compose up -d` starts MySQL 8.4 on host port **3307**, or point `FAMS_DB_URL` at any MySQL 8
   with a `fams` database and user. Create a second empty database, `fams_test`, and grant the same user access: the
   integration tests **wipe** it.
3. **Start everything.**

   ```
   powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1
   ```

   This starts MySQL (3307), the backend (8080) and the frontend (3000), skips what is already running, waits until each
   answers and prints the demo sign-ins. Stop with `scripts\stop-all.ps1` (`-Database` also stops MySQL). Logs are in
   `tools\`. After a reboot, run it again.
4. Open <http://localhost:3000>.

To run the pieces by hand: `scripts\dev-backend.ps1` (loads `.env`, runs the backend with the `dev` profile), then in
`frontend/` `npm install` and `npm run dev`.

### Demo accounts (dev profile only)

All have the old password. Never enable the `dev` profile in production.

| Role | E-mail |
|---|---|
| Administrator | `kavitha.reddy@dev.local` |
| Principal | `venkat.rao@dev.local` |
| HoD (CSE) | `padmaja.sharma@dev.local` |
| Lecturer | `anil.kumar@dev.local` |
| Assistant Professor | `priya.nair@dev.local` |
| Senior Assistant Professor | `rahul.verma@dev.local` |
| Associate Professor | `lakshmi.iyer@dev.local` |
| Professor | `srinivas.murthy@dev.local` |

## Repository layout

```
backend/    Spring Boot 3.5 / Java 25 modular monolith, one package per feature under edu.svec.fams
            (auth, admin, console, appraisal, section, scoring, report, audit, common, dev)
            src/main/resources/db/migration  Flyway migrations V1..V10
            config/                          checkstyle.xml, spotbugs-exclude.xml
frontend/   Next.js 16 app: src/app (routes), src/components, src/lib (api, validation, labels, form structure)
scripts/    start-all, stop-all, dev-backend, verify, backup, restore, audit-dependencies
docs/       this documentation
tools/      local logs, vendored Maven, backups, dev MySQL data (not source)
.github/    CI running the same checks as scripts/verify.ps1
```

## Everyday commands

| Task | Command |
|---|---|
| Run **every** check a change must pass | `powershell -ExecutionPolicy Bypass -File scripts\verify.ps1` (`-Offline` skips the two that need the internet) |
| Backend: compile, test, style, static analysis | `cd backend && mvn verify` |
| One backend test class | `mvn test -Dtest=WorkflowIntegrationTest` |
| Frontend: type check, lint, tests, build | `cd frontend && npm run check` |
| Frontend unit tests only | `npm test` |
| Dependency audit | `node scripts/audit-dependencies.mjs` (backend, OSV) and `npm audit --omit=dev` (frontend) |

**Integration tests need the environment loaded into the shell** (`FAMS_TEST_DB_PASSWORD`, and `FAMS_TEST_DB_URL` if the
test database is not on `127.0.0.1:3307`); `scripts\dev-backend.ps1` shows how `.env` is loaded.

What each check fails on is in [architecture.md](architecture.md#what-a-change-must-pass). A change is not done until
`verify.ps1` passes.

## Recipes

### Add or change a field in an existing section
1. Add the column in a **new migration** (`V11__add_x.sql`); never edit one that has run.
2. Add the field to the section in `backend/.../section/Sections.java`, for example
   `text("venue", "venue", "Venue", 200, false)`. The helpers are `text`, `integer`, `decimal`, `choice`, `date`,
   `monthYear` and `year`. **Required means the column is `NOT NULL`**, and allowed values must mirror the `CHECK`.
3. If the table should show it, add the key to that section's `columns` in `frontend/src/lib/formStructure.ts`.
4. Regenerate the contract snapshot and commit it:

   ```
   mvn test -Dtest=FrontendContractTest -Dfams.updateFixtures=true
   ```

   (This rewrites `frontend/src/lib/__fixtures__/meta.json`; `FrontendContractTest` fails whenever it stops matching the
   server, and the frontend's `contract.test.ts` checks every column the screens use against it.)
5. Run `verify.ps1`.

The form is drawn from `GET /api/sections/meta`, so no other frontend change is needed for a field to appear in the
add/edit dialog.

### Add a whole section
Add its table in a migration, then `SectionSpec.single(...)` or `SectionSpec.list(...)` in `Sections.java` (optionally
`.summary(...)` for computed totals, `.dateRange(...)` for a start/end pair). Add it to a page in `formStructure.ts`.
The printed report is built by `report/FormPdfBuilder`, so also add it there if it belongs on the paper form, and
`report/ReportLabels` for any enum labels.

### Change the approval chain
`AppraisalStatus.allowedNext` and `WorkflowAction` hold the rules; `AppraisalAccess` decides who sees what;
`frontend/src/lib/hierarchy.ts` describes the chain for the screens. The database also restricts `appraisals.status` and
`users.role` by `CHECK`, so a chain change needs a migration like V8, which also decides what happens to appraisals standing
at a removed status. Read V7 and V8 first, and [workflow.md](workflow.md).

### Change maximum marks
This is data, not code. An administrator publishes a new policy version in *Departments, years & scoring*. Seed data for a
fresh install is `V2__seed_reference_data.sql`; components are `V9__scoring_components.sql`.

### Add an API endpoint
Controller method (HTTP shape only) calling one `@Transactional` service method; role rule in `SecurityConfig` **and**
`@PreAuthorize`; anything touching an appraisal goes through `AppraisalAccess.loadVisible` / `loadEditable`; errors via
`ApiException` / `ValidationException`; write an audit entry for state changes. Add a test that shows the **refusal** for
the wrong role, not only the success.

### Add a migration
`backend/src/main/resources/db/migration/V<next>__short_name.sql`. Keep `CHECK`s in step with the Java side. If it creates
triggers it needs `log_bin_trust_function_creators=1` or `SUPER` for the migrating user.

## Conventions

The full list is in [architecture.md](architecture.md#conventions). The ones that bite:

- Java lines up to 180 characters, explicit imports, Checkstyle and SpotBugs are part of `mvn verify`.
- SQL values are always bound parameters; only table and column names that come from code are concatenated.
- Never log a password, token, session ID or appraisal content.
- Comments say why, not what.
- ESLint runs with `--max-warnings 0`.
- Do not run `npm audit fix --force`: it would downgrade Next.

## Configuration

Production defaults are in `application.yml`; developer-only settings in `application-dev.yml`. Every environment
variable is documented in [deployment.md](deployment.md#settings-environment-variables-of-the-backend) and
`.env.example`. The frontend reads `BACKEND_URL` (default `http://127.0.0.1:8080`) **when `npm run build` runs**.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Backend will not start: missing property | `FAMS_DB_URL` and `FAMS_DB_PASSWORD` have no default; load `.env` |
| Backend will not start outside `dev`: storage | `FAMS_STORAGE_DIR` is required and must be an absolute, writable path |
| Backend refuses `FAMS_FORWARD_HEADERS=framework` | Deliberate; use `native` behind a proxy, `none` otherwise |
| Flyway fails creating triggers | MySQL needs `log_bin_trust_function_creators=1` (or `SUPER` for the migration user) |
| Sign-in works but you are bounced back | Cookies are `Secure`; over plain HTTP set `FAMS_SECURE_COOKIES=false` (the `dev` profile already does) |
| Integration tests fail on connection | `fams_test` missing, or `FAMS_TEST_DB_*` not in the shell environment |
| `FrontendContractTest` fails | `Sections.java` changed; regenerate the snapshot (recipe above) |
| Everything is down after a reboot | Run `scripts\start-all.ps1` again |
| Flyway warns MySQL 8.4 is newer than tested | Known and harmless; production on 8.0 avoids the warning |
| A login says too many attempts | The sign-in limit; counters live in `sign_in_attempts` and expire after ten minutes |
