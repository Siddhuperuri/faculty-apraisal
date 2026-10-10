# Database reference

MySQL 8.x (8.4 in development), schema `fams`, managed entirely by Flyway migrations in
`backend/src/main/resources/db/migration`. There is no ORM: services run SQL through `JdbcClient`. Enumerated values and
ranges are `CHECK` constraints, so the database refuses what the application should already have refused.

This document describes the schema as it stands after migration `V16`. The migrations are the authority; if they and
this file disagree, the migrations win.

## Conventions

- Primary key `id BIGINT AUTO_INCREMENT` on every table except `sign_in_attempts`.
- Enumerations are `VARCHAR` with a `CHECK ... IN (...)`; the Java side mirrors them in `Sections.java` or an enum.
- Month-and-year values are `VARCHAR(7)` holding `YYYY-MM`, checked by regular expression.
- A migration that has run is **never edited**; a change is a new `V<n>__name.sql`.
- Tables that hold an appraisal's records cascade on delete from `appraisals` (only a draft can be deleted in practice);
  review, score and audit tables do **not** cascade, so reviewed work cannot vanish by accident.

## Entity overview

```
departments ──< faculty_profiles >── cadres
                    │ 1
                    │ n
 users 1──1 faculty_profiles          academic_years ──< scoring_policies >── cadres
 users ──< hod_assignments >── departments            │ 1
                                                      ├──< scoring_policy_criteria ──< scoring_policy_components
 faculty_profiles ──< appraisals >── academic_years    │
                          │  └── scoring_policy_id ───┘   (the policy version the appraisal started with)
                          ├──< appraisal_scores            (snapshot of the maxima; marks are calculated, none stored)
                          ├──< review_actions              (append-only)
                          ├── 1 appraisal_reports          (append-only, one per approved appraisal)
                          └──< 20 section tables           (Part A and Part B records)

 audit_logs        (append-only, no foreign keys)
 sign_in_attempts  (rate-limit counters, hashed keys)
```

## Identity and reference data

### `users`
| Column | Notes |
|---|---|
| `email` | Unique, the sign-in name; case-insensitive collation. The application also requires an exact lower-cased match at sign-in |
| `password_hash` | BCrypt. Nullable for a future externally-managed identity |
| `role` | `FACULTY`, `HOD`, `PRINCIPAL`, `DIRECTOR` (the Director Technical, at the Principal's level), `ADMIN`. `DEAN` and `VICE_PRINCIPAL` are allowed **only on a `DISABLED` account** (the withdrawn roles, kept for the record) |
| `status` | `ACTIVE` or `DISABLED` |
| `must_change_password` | Set on creation and reset; the user can do nothing but change it |
| `session_version` | Bumped on password change or reset; older sessions end |
| `password_changed_at`, `last_login_at` | Informational |
| `created_at`, `updated_at` | Timestamps |

### `faculty_profiles`
One per faculty user (`user_id` unique). Holds `employee_id` (unique), `name`, `contact_no`, `department_id`, `cadre_id`,
qualification and specialization, `phd_status` (`AWARDED`, `PURSUING`, `NOT_APPLICABLE`), joining dates, teaching /
industry / research experience in years (non-negative), and the research identifiers (ORCID, Scopus, Google Scholar,
Vidwan). It is the source Part A is pre-filled from.

### `departments`, `cadres`, `academic_years`
- `departments(code unique, name, active)`: seeded with the form's eight groupings (CE, ME, ECE, EEE, CSE, AIML, BSH, MBA).
  Departments are data, not code.
- `cadres(code unique, name, active)`: `LECTURER`, `ASST_PROF`, `SR_ASST_PROF`, `ASSOC_PROF`, `PROFESSOR`.
- `academic_years(name unique, start_date, end_date, active)`: `name` is `YYYY-YY`, `end_date > start_date`. `active`
  means open for new appraisals; a new appraisal goes into the most recent active year. `2025-26` is seeded (renamed from 2026-27 by V16).

### `hod_assignments`
`(user_id, department_id)` unique. Only an assigned HoD may review a department's appraisals. A department may have
several HoDs. (`dean_assignments`, added in V7 and unused since V8, remains for the record.)

## Scoring configuration

| Table | Purpose |
|---|---|
| `scoring_policies` | One per `(academic_year, cadre, version)`; `active`; the highest active version is used for new appraisals |
| `scoring_policy_criteria` | `(policy_id, criterion)` unique, `max_marks >= 0`, `calculation_mode` (`SELF_ENTERED` for all, `FORMULA` reserved), `configuration_json` reserved |
| `scoring_policy_components` | The components a criterion's maximum is made of (V9): `sort_order`, `description`, `max_marks >= 1`, foreign key to `(policy_id, criterion)`. Filled for criteria B1 to B5; 102 components seeded |

The nine `criterion` codes are listed in `scoring/Criteria.java`: `TEACHING_LEARNING`, `STUDENT_MENTORING`,
`FDP_CERTIFICATIONS`, `ADMINISTRATIVE`, `RESEARCH_PUBLICATIONS`, `FUNDED_PROJECTS`, `PATENTS_BOOKS_IPR`, `OUTREACH`,
`MEMBERSHIPS_AWARDS`. A cadre's maxima add up to 100 (enforced when publishing; seeded data is tested). See
[scoring.md](scoring.md).

## The appraisal

### `appraisals`
| Column | Notes |
|---|---|
| `faculty_id`, `academic_year_id` | Together unique: **one appraisal per faculty member per year** |
| `scoring_policy_id` | The policy version the appraisal started with |
| `status` | `DRAFT`, `SUBMITTED`, `HOD_REVIEW`, `HOD_APPROVED`, `PRINCIPAL_REVIEW`, `APPROVED` (CHECK) |
| `submitted_at`, `final_approved_at` | `APPROVED` if and only if `final_approved_at` is set (CHECK) |
| `declared_at` | When the form's Declaration was made (set at submission) |

Status changes are guarded updates (`UPDATE ... WHERE id = ? AND status = ?`), so concurrent reviewers cannot both act.
See [workflow.md](workflow.md).

### `appraisal_scores`
`(appraisal_id, criterion)` unique. Three separate concepts: `max_marks` (snapshot of the policy at creation),
`self_score` (the faculty member) and `review_score` (reserved for a separate panel; unused). CHECKs keep both scores
between 0 and `max_marks`. `remarks` is reserved.

### `review_actions` (append-only)
One row per transition: `actor_id`, `actor_role`, `action`, `from_status`, `to_status`, `comment` (up to 2000),
`created_at`; `from_status <> to_status`. Old rows keep naming the withdrawn Dean and Vice Principal steps exactly as they
happened.

### `appraisal_reports` (append-only)
The official PDF of an approved appraisal: `appraisal_id` unique, `storage_key`, `checksum` (SHA-256), `size_bytes`,
`generated_at`. Drafts are produced on demand and never stored. Each download re-checks the stored file against the
checksum.

## Form sections (Part A and Part B)

Twenty tables, one per section, in the official order. Each carries `appraisal_id` (cascade on delete). Four hold at most
one record per appraisal (`appraisal_id` unique): `general_information`, `student_mentoring`, `phd_progress`,
`other_contributions`. `research_metrics` is unique on `(appraisal_id, platform)`.

| Form reference | Section key | Table |
|---|---|---|
| Part A | `general-information` | `general_information` (a snapshot of the profile, editable while a draft) |
| B 1 | `teaching-courses` | `teaching_courses` |
| B 2 | `mentoring-summary`, `student-achievements`, `student-projects` | `student_mentoring`, `student_achievements`, `student_projects` |
| B 3 | `fdps`, `certifications` | `fdps`, `certifications` |
| B 4 | `administrative-roles`, `events` | `administrative_roles`, `events` |
| B 5 | `journal-publications`, `conference-papers`, `research-metrics`, `research-scholars`, `phd-progress` | `journal_publications`, `conference_papers`, `research_metrics`, `research_scholars`, `phd_progress` |
| B 6 | `funded-projects` | `funded_projects` |
| B 7 | `patents-ipr`, `books` | `patents_ipr`, `books` |
| B 8 | `outreach` | `outreach` |
| B 9 | `memberships-awards` | `memberships_awards` |
| B 10 | `other-contributions` | `other_contributions` |

Since `V33`: a course also has `course_role` (COORDINATOR or INSTRUCTOR) and `class_section` (A to E); both are NULL on courses
entered earlier and required whenever a course is saved. `fdps` and `certifications` no longer take start and end dates (the
columns stay, nullable, holding what was entered before); a certification has `duration_weeks` (whole weeks, backfilled from the
old free-text duration only where that was a plain number of weeks) and `platform_other` (required, and kept, only when the platform
is OTHER). The old `duration_weeks_hours` column is kept for history and no longer written.

Constraints you will meet: `end_date >= start_date`, counts and amounts `>= 0`, percentages 0 to 100, semester 1 to 12,
years 1950 to 2100, and each enumeration's allowed values. The field list, limits and labels of every table are defined
once in `section/Sections.java` and published at `GET /api/sections/meta`.

## Audit and security tables

### `audit_logs` (append-only)
`actor_id` (nullable: system actions), `action`, `entity_type`, `entity_id`, `metadata` (JSON), `created_at`. **No foreign
keys on purpose**, so history survives any later change to the rows it refers to. Indexed by entity and by actor.
Appraisal entries carry no file names or content.

### `sign_in_attempts`
Sign-in and password-change rate limits (V10), shared by every backend instance and surviving restarts.
`key_hash` (SHA-256 of the limiter key: no e-mail or address is stored), `attempts`, `window_start_ms`, `refused`. A row is
dead ten minutes after its window starts and is purged; the table is bounded at 100 000 keys.

## Triggers: append-only history

`audit_logs`, `review_actions` and `appraisal_reports` each have `BEFORE UPDATE` and `BEFORE DELETE` triggers that raise
`SQLSTATE 45000`. Consequences:

- The application database user must hold only `SELECT, INSERT, UPDATE, DELETE`. **`DROP` and `TRUNCATE` bypass the
  triggers**, so never grant them. Run migrations with a separate account (`SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD`).
- Creating triggers on a binlog-enabled server needs `log_bin_trust_function_creators=1` or `SUPER` for the migration
  user. The compose file sets the former.

## Migration history

| Version | Adds |
|---|---|
| V1 | Core: users, profiles, departments, cadres, years, HoD assignments, scoring policy, appraisals, scores, review actions, audit log, append-only triggers |
| V2 | Seed: eight departments, five cadres, the 2026-27 year (renamed 2025-26 in V16) and the cadre scoring policy (each totals 100) |
| V3 | The twenty section tables (and `supporting_documents`, dropped again in V15) |
| V4 | Document categories, note, content-type and checksum constraints on `supporting_documents` (dropped in V15) |
| V5 | `appraisal_reports` (append-only) |
| V6 | Account security: forced password change, session version, last login |
| V7 | Dean and Vice Principal levels (later withdrawn) |
| V8 | Chain reduced to faculty, HoD, Principal; stranded appraisals and accounts moved, each with an audit entry; constraints narrowed |
| V9 | `scoring_policy_components` and the B1 to B5 breakdown per cadre |
| V10 | `sign_in_attempts` and `users.last_login_address` |
| V11 to V14 | Audit trail made deletable; declaration without a place; lecturer workload marks; marks per entry (see the migration files) |
| V15 | `supporting_documents` dropped: Section 12 was removed from the form |
| V16 | The seeded academic year renamed from 2026-27 to 2025-26 (dates 2025-06-01 to 2026-05-31) |
| V17, V18 | Contact number, name and employee ID on the account itself (for roles without a faculty record) |
| V19 to V21 | The HoD's messages to the faculty member, their edits, and the kept earlier wordings |
| V22 | Administrator operations: import history, restore tests, handover notes |
| V23 | The Director Technical role (`DIRECTOR`) added to the roles a user may have |

## Working with the schema

- **Add a column or table:** write the next migration. Test databases are rebuilt from the migrations, so a failing
  migration shows up in `mvn verify` (`SchemaIntegrityTest`, `SectionSchemaTest`).
- **Backups** copy the database and the stored files together and verify every file against the checksum the database
  holds (`scripts/backup.ps1`, `scripts/restore.ps1`; see [deployment.md](deployment.md)).
- **Never** edit a row of an append-only table, and never "fix" a status by hand: use the application so the audit and
  review rows stay consistent with it.
