# Architecture

How the code is organised, where each kind of rule lives, and what a change has to pass. Read `docs/workflow.md` for
the appraisal states and `docs/security.md` for the protections; this file is the map.

## The pieces

```
 browser ──► reverse proxy (TLS) ──► Next.js (pages, frontend/)
                              └────► Spring Boot (API, backend/) ──► MySQL
                                                              └────► document storage directory
```

- **Frontend** (`frontend/`): Next.js 16, React 19. Presentation only. Every rule it applies (which role sees what,
  which values are valid) is repeated from the server for convenience; the server is the authority.
- **Backend** (`backend/`): Spring Boot 3.5 on Java 25. A JSON API under `/api`, session cookies, MySQL through
  plain JDBC (`JdbcClient`), schema managed by Flyway (`src/main/resources/db/migration`).
- **No other services.** Nothing calls out to the internet at run time (see `docs/deployment.md`).

## Backend packages

One package per feature, not per technical layer. Inside a package: a controller (HTTP shape only), a service (rules,
transactions and SQL), and the types they share.

| Package | Responsibility | Start reading at |
|---|---|---|
| `auth` | Sign-in, sessions, CSRF, password rules, the security filter chain | `SecurityConfig`, `AuthController`, `SessionGuardFilter` |
| `appraisal` | The appraisal's life cycle and **who may see or edit one** | `AppraisalAccess`, `AppraisalStatus`, `WorkflowAction` |
| `section` | The form: every section and field, validation, generic read and save | `Sections`, `FieldSpec`, `SectionService` |
| `scoring` | The nine criteria, each cadre's maxima and scoring components, and the marks worked out from the entries | `Criteria`, `ScoreService` |
| `documents` | Private file storage (used for the issued reports) | `DocumentStorage`, `LocalDocumentStorage` |
| `report` | The printed form as a PDF; the official copy and its integrity check | `ReportService`, `FormPdfBuilder` |
| `console` | Read-only overviews for the HoD, Principal and administrator | `ConsoleService` |
| `admin` | Accounts, departments, academic years, scoring policy | `AdminUserService`, `AdminSetupService` |
| `audit` | The append-only audit trail: writing it and the administrator's view of it | `AuditService`, `AuditQueryService` |
| `common` | The error shape, request filters, start-up checks, small JDBC helpers | `GlobalExceptionHandler`, `RequestSizeLimitFilter` |
| `dev` | Demo accounts. Active only with the `dev` profile | `DevDataSeeder` |

Dependencies point one way: feature packages use `auth`, `audit` and `common`; `common` uses nothing of the
application. Everything that touches appraisal content goes through `appraisal.AppraisalAccess`. One package-level
cycle remains and is deliberate: `appraisal` shows the scores in an appraisal's view, and `scoring` asks `appraisal`
whether the caller may edit.

## The path of a request

```
RequestIdFilter            gives the request an ID (in every log line, in the response, in 500 bodies)
RequestSizeLimitFilter     refuses bodies over 1 MB (the application takes no file uploads)
Spring Security chain      CORS (off unless configured) → CSRF (header only) → session
  SessionGuardFilter       re-checks the account in the database: disabled, re-roled, password changed, change pending
  authorization            URL rules per role area (SecurityConfig)
Controller                 reads the request, calls one service method, shapes the response
Service                    @Transactional; validates; asks AppraisalAccess; runs SQL; writes the audit entry
GlobalExceptionHandler     turns every failure into {"message": ...} with the right status
```

## Where each kind of rule lives

| Rule | Lives in | Not in |
|---|---|---|
| Who may open or edit an appraisal (ownership, department, stage) | `AppraisalAccess.loadVisible` / `loadEditable` | controllers, SQL scattered through services |
| Which role may call an area (`/api/admin/**` ...) | `SecurityConfig` URL rules, repeated as `@PreAuthorize` on the controller | the frontend |
| What state may follow what, and who may move it | `AppraisalStatus.allowedNext`, `WorkflowAction` | the service's `if` statements |
| What a field accepts | `FieldSpec` (one definition, published at `/api/sections/meta`) | the frontend (it reads the published definition) |
| The shape of an error | `GlobalExceptionHandler`; `ErrorResponses` for filters | individual controllers |
| How a password is judged | `PasswordPolicy` | the frontend (it shows hints only) |

Missing records and records the caller may not see both answer `404`, so an ID cannot be probed for existence.

## Persistence

There is no ORM. Services run SQL through `JdbcClient`, in text blocks, inside `@Transactional` methods.

- **Values are always bound parameters.** Where SQL is assembled, the assembled parts are table and column names that
  come from code (`Sections`, an enum, a constant), never from a request. Keep it that way.
- **The database is the last line of defence**: CHECK constraints mirror the allowed values and ranges, unique keys
  back every "already exists" message, and triggers make `audit_logs`, `review_actions` and `appraisal_reports`
  append-only.
- **Concurrency**: a write to an appraisal locks its row first (`SELECT ... FOR UPDATE` in `AppraisalAccess`), and a
  status change is a guarded `UPDATE ... WHERE status = ?`, so two reviewers cannot both act on the same state.
- **Schema changes** are new Flyway migrations (`V8__...sql`); a migration that has run is never edited.
- An insert that needs its generated key goes through `GeneratedKeys.insert`.

## Configuration

`application.yml` holds the production defaults; anything right only on a developer's machine is in
`application-dev.yml`. Starting without a profile therefore gives: cookies marked Secure, no cross-origin access,
the backend listening on this machine only, no demo accounts. The database URL and password have no default.
Every setting is listed in `docs/deployment.md`.

## Conventions

- Java: standard naming (PascalCase types, camelCase members, UPPER_SNAKE_CASE constants), explicit imports, lines up
  to 180 characters, `if (x) return y;` on one line is fine, anything longer takes braces. The build checks this
  (`backend/config/checkstyle.xml`).
- Comments say why, not what. Javadoc on a class says what it is responsible for.
- Request and response types are records nested in the service or controller that owns them.
- Errors: throw `ApiException` (a status and a message that is safe to show) or `ValidationException` (per-field
  messages). Never return an error body by hand from a controller. Never catch an exception just to hide it.
- Logging: `log.warn` for something an administrator should look at, `log.info` for security events that are normal
  (a sign-in). Never log a password, token, session ID or the content of an appraisal.
- Tests: one class per feature under `backend/src/test`, named for the behaviour they pin down, running against a real
  MySQL schema (`fams_test`). A security rule gets a test that shows the refusal, not only the success.

## What a change must pass

`scripts/verify.ps1` runs everything; `.github/workflows/ci.yml` runs the same checks in CI.

| Check | Tool | Fails on |
|---|---|---|
| Compile | `javac -Xlint:all` | any warning |
| Tests | JUnit 5 with a real MySQL (`mvn verify`); Vitest (`npm test`) | any failure |
| Style | Checkstyle (`backend/config/checkstyle.xml`); ESLint (`npm run lint`) | any violation or warning |
| Static analysis | SpotBugs with Find Security Bugs | any finding not excluded with a reason in `backend/config/spotbugs-exclude.xml` |
| Types | `tsc --noEmit` | any error |
| Build | `mvn verify` packages the jar; `next build` | failure |
| Known vulnerabilities | `scripts/audit-dependencies.mjs` (OSV database; reviewed exceptions in `scripts/accepted-advisories.json`); `npm audit --omit=dev` | any advisory not accepted there |
