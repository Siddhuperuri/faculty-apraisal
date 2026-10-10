# Architecture decisions

**ADR-001: Modular monolith.** One Spring Boot application with package-per-domain (`auth`, `appraisal`, `audit`,
`common`). The system's complexity does not justify distributed infrastructure.

**ADR-002: Scoring policy is data, versioned, and snapshotted.** Maximum marks live in `scoring_policy_criteria`;
each appraisal copies them at creation. Policy changes cannot rewrite history.

**ADR-003: Repeatable form sections are real tables, not JSON.** The paper form's limited rows are a presentation
constraint. Each section has a table with typed columns and CHECK constraints.

**ADR-004: Document storage behind an abstraction.** The database holds metadata and an opaque storage key only.
(Not yet implemented.)

**ADR-005: Workflow transitions are a guarded SQL update plus an in-code state machine.** The state machine rejects
illegal moves with a clear error; the `WHERE status = ?` guard makes concurrent moves safe without locks or versions.

**ADR-006: JdbcClient for the appraisal workflow, JPA only for `User`.** The workflow is a handful of explicit,
set-based statements where SQL is clearer and safer than entity graphs. JPA remains for plain entity CRUD where it fits.

**ADR-007: Session cookies, not JWT.** A browser SPA on the same site gets revocation, rotation and `HttpOnly` storage
for free. No cross-site API consumers are planned.

**ADR-008: Database enforces what it can.** CHECK constraints for enums, ranges and formats, and triggers that make
the audit and review history append-only, so a bug in application code cannot corrupt them.

**ADR-009: Not-found instead of forbidden for inaccessible records.** Avoids revealing which appraisals exist.

**ADR-010: Pre-release schema edited in place.** `V1` to `V3` were corrected directly (and development databases
recreated) because nothing was deployed. **From the first deployment onward, schema changes must be new migrations.**

**ADR-011: One declarative section engine instead of 19 hand-written endpoints.** Each Part B table is described once in
`Sections.java` (fields, labels, limits, allowed values). A single service validates, diffs and saves them, so a new
field is one line plus a migration. Safety rests on identifiers coming only from code, values always being bound
parameters, and tests that compare every definition with the real schema (columns, enum values, text lengths).
Trade-off: the SQL is assembled from definitions, so it is deliberately generic; anything genuinely different (documents,
scores) gets its own service.

**ADR-012: Section save = whole list, diffed.** Faculty edit a list in the UI and the section is saved as a unit
(matches the schematic's section-level autosave). Diffing keeps ids stable, makes an unchanged resave a no-op and keeps
the audit trail free of noise. The appraisal row is locked for the save, so a save cannot interleave with a submit.

**ADR-013: The server describes the forms; the browser draws them.** `GET /api/sections/meta` publishes every field's
label, type, limits and allowed values. The frontend holds only presentation (grouping, headings, columns). Client-side
checks reuse the server's limits and wording for instant feedback, and the server stays authoritative. A contract test
(frontend) plus a snapshot test (backend) stop either side drifting.

**ADR-014: Same-origin API through the frontend.** Next rewrites `/api/*` to the backend, so the session and CSRF
cookies are first-party, `SameSite=Lax` works and CORS is not needed. In production put both behind one reverse proxy.

**ADR-015: The declaration is part of submitting.** `POST submit` requires an accepted declaration and is the
only way into SUBMITTED; the review-step endpoint refuses SUBMIT. Every submission therefore carries one.

**ADR-016: Part A is snapshotted per appraisal.** It starts from the faculty profile but lives with the appraisal, so an
approved appraisal keeps what was true at the time even if the profile later changes.

**ADR-018: Self-scores are typed in, bounded by the policy snapshot, never calculated.** The form defines maxima but no
formula, so the system enforces range, precision and the zero-maximum case and nothing else. Saves are all-or-nothing and
partial (only criteria sent change), run under the appraisal's row lock like section saves, and audit only real changes.
Criterion names live in one enum (`Criteria`) and travel in the API, so the frontend keeps no copy of the form's wording.

**ADR-017: Visual direction, "the college register".** The product brief asks for restrained institutional software, so the
design is distinctive through craft rather than decoration: warm ivory paper with a faint grain, navy ink and the
college blue from the official form, a thin ochre rule taken from the seal's gold, a single typeface, Montserrat (self-hosted
variable font, SIL OFL), for titles, numerals and data alike, so nothing looks out of place. Signature details are the outlined section
numerals, the ledger-style contents, status shown as a rubber stamp (always with its word), and an eleven-mark page
ruler that shows where entries exist instead of an invented "percent complete". Motion is limited to one staggered
entrance and a hover lift, and is switched off by `prefers-reduced-motion`. Tokens live in `frontend/src/app/globals.css`;
the sidebar contents appear from 1280px up so tables always get full width below that. The college seal and the
accreditation lines come from the official form. Fonts are loaded through `next/font` (self-hosted at build time).

**ADR-019: Supporting documents were removed (superseded).** The system once let faculty upload evidence files (PDF,
PNG, JPEG, content-checked, stored privately, served as attachments). At the principal's office's request the whole
feature was taken out: the Supporting Documents page and its upload, download and delete endpoints, the document
checklist on the PDF, the `supporting_documents` table (migration `V15`) and the upload limits. As a result no request
carries a file any more, so `RequestSizeLimitFilter` now caps every body at 1 MB. `DocumentStorage` stays: it keeps the
issued reports (ADR-020, ADR-029). Files uploaded earlier remain in the storage directory but nothing refers to them.

**ADR-020: The official report is generated once, stored, and then served unchanged.** The PDF mirrors the six-page form.
Until final approval a request returns a fresh copy watermarked DRAFT. At the first request after final approval the
server generates the report, stores it with its checksum in `appraisal_reports` and from then on serves those bytes, so
the document an approver signed off is the document everyone receives, even if the code or the faculty profile later
changes. The table is unique per appraisal and immutable (triggers reject UPDATE and DELETE); two simultaneous first
requests produce one report. The PDF uses the standard WinAnsi fonts, so characters outside that set print as "?" (a
known limitation; embedding a Unicode font would lift it).

**ADR-021: Account state is checked on every request, not only at sign-in.** The session stores the principal, but a
filter (`SessionGuardFilter`) compares it with the user row on each request: a disabled account or a changed role ends
the session at once (401), and so does a session older than the last password change or reset (`session_version`).
While an administrator-issued password is pending (`must_change_password`) everything except changing it, `me`, `csrf`
and sign-out is refused with 403 `PASSWORD_CHANGE_REQUIRED`. The cost is one primary-key lookup per request. This closes
the earlier gap where role and disable changes applied only at the next login.

**ADR-022: Administration creates accounts; it never sees passwords or appraisal content.** There is no self-registration.
An administrator creates each account (faculty with their record, HoDs with their departments, the Principal, other
administrators) with the old password (ADR-033); only a BCrypt hash is stored, and
passwords are never logged, audited or listed. The user must replace it at first sign-in. A role never changes after
creation (a new account is made instead), e-mail is the sign-in name and is immutable, an administrator cannot disable or
reset their own account, and an administrator can read no appraisal: the audit view they get leaves out file names and
checksums of appraisal entries. The first administrator on a fresh installation comes from `FAMS_BOOTSTRAP_ADMIN_EMAIL`
and `FAMS_BOOTSTRAP_ADMIN_PASSWORD`, used only when no administrator exists. Departments, academic years and scoring
policies are changed through the same area: nothing is deleted (closed instead), a policy is never edited but published
as the next version, and every cadre's marks must total 100 as on the form.

**ADR-023: One console per level of the hierarchy, each showing only what that level may see.** Faculty, Head of the
Department, Principal and administrator each land on their own console. The numbers come from the server
(`ConsoleService`), grouped into five stages (not yet submitted, with the HoD, returned to faculty, with the Principal,
approved). The visibility rules of the appraisals themselves carry over: an HoD sees their departments only and a draft
appears as "not yet submitted" with nothing to open, so its existence is not revealed; the Principal sees individual
appraisals only from HoD approval onward and everything earlier is one aggregate ("not yet with you"); the administrator
sees counts by stage, set-up checks and recent activity, never a name next to an appraisal or any content. Faculty
included in a console are active accounts, plus anyone with a submitted appraisal.

**ADR-024: The deployment target is the college network only.** Nothing may need the internet at runtime (no CDN, hosted
fonts, analytics, mail or external login), builds are done on a connected machine and copied in, and accounts are issued
by administrators rather than self-registered. The network is still shared with students, so TLS, the per-address rate
limit (which needs `FAMS_FORWARD_HEADERS` behind a proxy), forced password change and immediate session invalidation
stay. See `docs/deployment.md`.

**ADR-025 (superseded by ADR-026): The Dean and the Vice Principal are levels in the approval chain.** The chain became faculty -> HoD -> Dean ->
Vice Principal -> Principal at the college's request (the printed form shows only the HoD and Principal boxes). Each new
level has the same three steps as the HoD (begin, return with a reason, recommend) and its own states, so the state
machine stays a plain table and every transition keeps its guarded update and history row. A Dean covers the departments an
administrator assigns (`dean_assignments`, like an HoD); the Vice Principal covers the whole college. Visibility moves up with
the chain (each level sees an appraisal from the approval of the level below), and the frontend reads the chain from one table
(`lib/hierarchy.ts`) while the server stays the authority. The printed report gains "Remarks of the Dean" and "Remarks of the
Vice Principal" boxes between the HoD's and the Principal's; reports already issued are immutable and keep their original
layout. Appraisals that were waiting at HOD_APPROVED when migration V7 ran now wait for a Dean.

**ADR-026: The chain is faculty -> HoD -> Principal, and nothing is returned.** At the college's request the Dean and Vice
Principal levels of ADR-025 and every "return to the faculty member" step were withdrawn (migration V8). The state
machine is now a straight line (DRAFT, SUBMITTED, HOD_REVIEW, HOD_APPROVED, PRINCIPAL_REVIEW, APPROVED): the HoD may
comment and approves, which forwards the appraisal; the Principal's approval is final; a submitted appraisal is never
editable again. The roles, statuses, endpoints, consoles and screens of the withdrawn levels are gone, on the server as
well as in the browser, and the database refuses the old statuses and any active account of the old roles. History is
kept as it happened: the append-only tables still name the old steps, the screens and the report still read them, and
only what would otherwise be stranded was moved, with an audit entry each (returned appraisals back to DRAFT, appraisals
with or past the Dean or Vice Principal to HOD_APPROVED, the two roles' accounts closed). See `docs/workflow.md`.

**ADR-027: Scoring components are policy data, per cadre.** The college's cadre-wise document breaks the maxima of
criteria B1 to B5 into components. They are stored with the scoring policy (`scoring_policy_components`, seeded from the
document by a generated migration) and read in one place (`ScoreService.rows`), so the score sheet and the report show
the components of the appraisal's own cadre and policy version and the frontend keeps no copy. The document's maxima
are the ones Annexure A already had, so nothing was rescaled; it stops at B5, and the other four criteria keep their
maxima (which is what totals 100) with no breakdown. Self-scores remain one per criterion. See `docs/scoring.md`.

**ADR-028: Sign-in limits live in the database.** The counters of ADR-024's sign-in limits moved from each server's
memory to a table (`sign_in_attempts`), and the address an account usually signs in from to `users.last_login_address`,
so several instances share one set of limits and a restart forgets nothing. MySQL is the store because it is the one
piece of shared infrastructure the system already has. Each change is a single conditional statement (atomic in the
database), run outside the caller's transaction so a counted attempt survives a rollback; keys are stored as SHA-256.

**ADR-032: The workload is 2.5 marks a course and 8 courses are the minimum.** The college corrected ADR-031's first reading (5 marks, 4 courses): an academic year has 2 semesters and a faculty member teaches 4 courses in each, so 8 courses are required and each earns 2.5, reaching the workload component's 20. The minimum is a total of 8 for the year, not checked per semester. Per-course marks are therefore decimals (`MARKS_PER_COURSE`, `ScoringRules.Line`).

**ADR-031: B5 to B9 are marked per entry with no maximum; B1 to B4 keep fixed maxima.** At the college's instruction the
cadre totals no longer have to be 100. The marks for each paper, project, book, event and so on are a fixed rate card in
code (`ScoringRules`, `docs/scoring.md`), calculated from the entries and shown live; the faculty member may overwrite a
calculated score. B1's maxima are 40, 40, 35, 30 and 30, with workload awarded 2.5 marks a course (at least 8 courses, 4 in each of the year's 2 semesters, are
needed to submit). B2 to B4 have no per-entry marks and are typed. The college did not say how marks for B2 to B4 should be
calculated; this is the working choice until it does.

**ADR-030: The audit trail can be deleted by an administrator.** At the college's request an administrator can delete one entry, every entry of one kind, or all of them (migration V11 drops the delete trigger; the update trigger stays, so entries cannot be edited). The deletion is itself recorded (`AUDIT_DELETED`, with the number removed), so the trail always shows that it was cleared and by whom, though not what it held. Review history and issued reports stay append-only. This weakens the audit trail's value as evidence: see `docs/security.md`.

**ADR-029: Stored files need a named, persistent directory, and are backed up with the database.** `FAMS_STORAGE_DIR` has
no default outside development and must be absolute; the backend refuses to start without a directory it can write to.
Writes are forced to disk before the database refers to them. `scripts/backup.ps1` and `scripts/restore.ps1` treat the
database and the directory as one unit and verify every file against the checksum the database holds.


**ADR-033: One old password for every new account, and accounts can be created from a CSV file.** To make creating accounts in bulk
practical, every account an administrator creates, and every account whose password an administrator resets, starts with
one standard password, `Srivasavi@123` (`DefaultPassword`; `FAMS_DEFAULT_PASSWORD` overrides it), instead of a random
one-time password that had to be copied out and handed over one by one. The account still has `must_change_password`, so
the person must replace it at first sign-in, and the password rules refuse the standard password as a choice. The cost
is that until a person has signed in, their account can be opened by anyone who knows their e-mail address and the
standard password (`docs/security.md`). The bootstrap administrator keeps the password the installer chose. Accounts
are created from a CSV file with the columns Name, College e-mail address, Role, Employee ID, Contact number, Department
and Designation (cadre) (`AccountImportService`): every row is checked first, with the rules for adding one by hand, and
the accounts are created only if all rows are right, so a half-right file leaves nothing to clean up. The password is
hashed once per file. Accounts without a faculty record (HoD, Principal, administrator) keep the name and employee ID on
`users` (migration V18).

**ADR-034: The HoD can message the faculty member during review, without returning the appraisal.** A Head of the Department
who has a query, or cannot approve yet, needs to tell the faculty member and ask them to meet, inside the website. The
rule that nothing is returned stands (ADR in `docs/workflow.md`): a message is a note, not a status change, so the
appraisal stays `HOD_REVIEW` and locked. It can be sent only between beginning the review and approving (the row is
written only if the appraisal is in that status at that moment), only by the HoD of the faculty member's department, and
is readable only by them and the faculty member (the Principal and administrators cannot read it). The HoD who wrote a
message can correct it while the appraisal is still `HOD_REVIEW` (not a colleague's, and not after approval); the faculty
member is then shown it as edited and as unread again. Every earlier wording is kept in `appraisal_message_versions`
(written in the same transaction as the edit, append-only by database trigger like the review history) and is shown to the
HoDs of the department under the message; the faculty member is told it was edited but is not shown the earlier text (one
place to change, `MessageService.list`, if the college wants them to see it). A message cannot be deleted.
The audit trail records that one was sent or edited but not its text. The appraisal is shown as "Query raised" from the first
message until approval, but this is derived from the messages (`queryRaised`), not a new workflow status: a new status
would touch the transitions, the Principal's view and every console count for what is only a label, and the chain stays
exactly as it was. It is one way: there is no reply, because
the meeting is held in person. The faculty member opening it marks it seen, so the HoD knows. It is deliberately separate
from the comment written with an approval, which the faculty member still cannot see.

**ADR-035: The Director Technical decides at the Principal's level, with a status-based hand-off and a box of their own.** At the
college's request a Director Technical was added with a console that is a clone of the Principal's (migration V23, role
`DIRECTOR`). The college chose to put the Director at the Principal's level rather than after the Principal, so the chain
stays faculty -> HoD -> Principal or Director Technical and **no status was added**: an appraisal the HoD has forwarded
(`HOD_APPROVED`) can be taken up by either, and either one's approval is final. It is the status, not the person, that
decides what may happen next, so whichever of them begins the review the other may finish it, and the second to try to begin
gets 409. The two have separate workflow steps (`START_DIRECTOR_REVIEW`, `DIRECTOR_APPROVE`) so that the review history and
the audit trail record who acted; `review_actions.actor_role` (16 characters) is the reason the role is `DIRECTOR`, not
`DIRECTOR_TECHNICAL`. The college asked for a "Remarks of the Director Technical" box to be printed on every report, so
reports carry a box for the Principal and one for the Director and the one that did not act is left empty; official copies
already issued are stored bytes and are not regenerated. The Director's console is served by the Principal's
`ConsoleService.principal` under `/api/director/console` (each role has its own URL rule, so neither can open the other's),
and the browser draws both with one `ApprovalConsolePage`. Rejected: putting the Director after the Principal (it would
remove the Principal's final say and add two statuses, an extra faculty step and a decision about every appraisal already at
the Principal), and a read-only Director (not what was asked for).

**ADR-034: Automatic marks, the academic-year rule, resubmission after a query, and no sign-in limit (2026-10-09).** Decided by the user in
one pass. (1) Marks: every criterion is calculated from the entries and nobody types a score; ADR-018 no longer holds and
`self_score` is gone (see `docs/scoring.md` for the rates, B2 to B4 being a working rule the college has yet to confirm). At most 8
courses can be added and B1 is an eighth of its maximum a course. (2) Only what was achieved, done or received between 1 June and
31 May of the appraised year is accepted and counted. (3) While the HoD is reviewing and has sent a message, the faculty member may
correct the appraisal and send it again (`RESUBMIT`, HOD_REVIEW -> SUBMITTED); this is the only backwards step (`docs/workflow.md`).
(4) Sign-in attempts are no longer limited: the brute-force defence described in docs/security.md is removed at the user's request, knowingly, on a
network the students share; the change-password guess limit stays. (5) Form changes: program and branch are lists (a branch must
belong to its program), the number of sections of a course is gone, the days of a programme are worked out from its dates, roles
carry from and to dates, the author position is a number 1 to 8, a project may have the outcome "None", and mentees are 0 to 50.
