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
(The earlier rule that held an account with a pending password at the change screen, 403 `PASSWORD_CHANGE_REQUIRED`, was
removed on 2026-10-13: ADR-040 makes the change optional.) The cost is one primary-key lookup per request. This closes
the earlier gap where role and disable changes applied only at the next login.

**ADR-022: Administration creates accounts; it never sees passwords or appraisal content.** There is no self-registration.
An administrator creates each account (faculty with their record, HoDs with their departments, the Principal, other
administrators) with the old password (ADR-033); only a BCrypt hash is stored, and
passwords are never logged, audited or listed. The user must replace it at first sign-in. A role never changes after
creation (a new account is made instead), e-mail is the sign-in name and an administrator may correct it later (2026-10-15: the person is signed out, the
change is audited as USER_EMAIL_CHANGED, and an administrator cannot change their own address), an administrator cannot disable or
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
one-time password that had to be copied out and handed over one by one. The account still has `must_change_password`, which now only
recommends a change (ADR-040), and the password rules refuse the standard password as a choice. The cost
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

**ADR-036: Course details, FDP and certification durations, B1 re-divided, and mentoring marks (2026-10-10).** Decided by the user
in one pass. (1) A course records the faculty member's role in it (course coordinator or instructor, one of the two), its section
(A to E), a semester chosen from the list and hours a week chosen from 1 to 6; older courses lack role and section until edited.
(2) B1 has no "student feedback" component: policy version 3 (`V33`) divides B1 into workload, course file and innovative
practices (20/10/10, 20/10/5 and 15/10/5 by cadre; totals 40, 35 and 30 unchanged). Workload is rounded to a whole mark, so a
Professor's 1.875 a course gives 15 for 8 courses and a whole number below that; the other components are not rounded. Submitted
and approved appraisals keep the version they began with; drafts move to version 3. (3) Mentoring is 6 marks when any student
is mentored; project guidance is 2 a project up to 4; student achievements are unchanged. (4) A workshop, FDP, seminar or
training program has a duration in days instead of dates; one of 5 days or more earns the whole 5 marks and more add nothing
(shorter ones are recorded and earn nothing). A certification has a duration in whole weeks, and one valid certification
earns the whole 10; "Other" platform asks for its name. (5) "Date of joining (present designation)" is now "Date of promotion"
and optional; "Conference papers presented" is "published". Assumption recorded: marks of every appraisal, including submitted
ones, are recalculated from the current rate card on reading (as before), so only the B1 components, which are data, are held
to the version an appraisal began with.

**ADR-037: Dropdown and reference data live in the database (2026-10-11).** Decided by the user. Every choice list on the appraisal
form (program, branch by program, section, semester, hours a week, course type and role, platforms, levels, statuses, outreach
roles, ...) and the suggested words while typing a role or an awarding body are rows in `reference_lists` / `reference_values`
(migration V34, which seeds exactly the lists that were in code and drops the CHECK constraints that duplicated them), read through
`ReferenceData`, served with the form definitions, and managed by an administrator under Admin > Dropdown lists
(`/api/admin/lists`): add a value (available at once), change its wording on the form and on the printed report, reorder, switch
off. Values are never deleted, so a record that holds a switched-off value keeps it and its label. Values the application's rules
depend on are locked: their wording is editable, they cannot be switched off, and a value added to such a list earns nothing until
the scoring code is told about it (scoring rates and the branch-to-program rule of the chain are not data). The consequence worth
knowing: the database no longer rejects a misspelt value by itself; the application is the only writer and checks every value.
Not moved: workflow statuses, user roles, scoring criteria (all part of the application's behaviour), and departments, cadres and
academic years (already database-managed under Admin).

**ADR-038: Revoke, caps, roles, ECAP courses, password-reset links, session timeout, and the administrator's dashboard (2026-10-11).**
Decided by the user in one pass; where the new rule conflicts with an old one the new one wins. (1) The HoD may revoke an
appraisal at any stage before final approval, including one forwarded to the Principal, with no consent needed; submitted or
in review goes back to the faculty member as a draft, forwarded comes back to the HoD; always with a reason and always audited;
this replaces "there is no way back" for the HoD only (the Principal and the Director Technical still have none). (2) Funded
projects: at most 2 entries and 10 marks a year; Outreach and Memberships have policy-driven limits; the limits and the
institutional-role marks live in the scoring policy (V35). (3) Courses are chosen from ECAP and never typed: a new or changed
course must exist in ECAP and takes its name from it; an unchanged course saves while ECAP is down (assumption: the real API's
paths and fields are configuration, FAMS_ECAP_*). (4) ISBN is mandatory for books and chapters and must be a valid ISBN-10 or
ISBN-13; older entries without one are asked for it the next time the list is saved. (5) There is no Score & Review page for
faculty: the last page is "Submit"; reviewers keep the score sheet; scores and the report are unchanged. (6) Sessions end after 30
minutes of inactivity with a two-minute warning; what was typed into an open form is kept in the tab and offered after signing in.
(7) Forgot password: a one-time link, 30 minutes, token stored as SHA-256, rate limited, same answer for any address, sent by
SMTP when configured (FAMS_MAIL_*); a password is never e-mailed. (8) Admin: "Accounts" is "Users" on screen (routes unchanged),
users can be given another role (audited, sessions ended), each department is shown separately, and an Analytics page shows
aggregated progress with charts (counts only). (9) The standard password flow and the administrator's reset stay as they were.

**ADR-039: Choosing several rows; appraisal-details export; reset links in bulk (2026-10-12).**
Decided by the user in one request; where it conflicts with an older rule the new one wins. (1) Tables in the HoD console and the
administrator's Users list have a checkbox per row, a header checkbox for what the filters show, a bar of actions that exists only
while something is chosen, and a choice kept by id across search, filter and sort. (2) The appraisal details of chosen faculty can
be downloaded as a real .xlsx workbook (title with year and department, styled and frozen header, sized columns, wrapped text, real
dates) or CSV, one row per faculty member in the 15-column department-review layout; several records in a cell are a numbered
list. An HoD may export only faculty of their own departments and an administrator any; one id outside the caller's reach makes
the whole request 404 (the same answer as an unknown id), so the ids sent from the browser are never trusted. (3) This **replaces
the rule that administrators never read appraisal content**: they may export it (not open or edit an appraisal; `AppraisalAccess`
still refuses them). A **draft stays private to its author** for everyone: it is listed as "Not submitted" with no content (the
request did not address drafts; the existing rule of ADR on HoD visibility is kept). (4) The administrator's bulk "set these
passwords back to the standard one", which displayed the standard password, is **removed** and replaced by "Send password reset
link": the one-time 30 minute link of the forgot-password flow, e-mailed to each chosen active user, with a summary of sent,
skipped and failed, rate limited per administrator and per user, audited, never revealing a password; the administrator's own
account is excluded. The start-up token `FAMS_RESET_ALL_PASSWORDS` remains for when nobody can sign in or no mail is set up. (5) No
new library: the workbook is written directly (Office Open XML), and CSV cells that start with `= + - @` get a leading apostrophe.
(6) No migration: exports and links use existing tables, and the audit trail records `EXPORT_APPRAISALS`, `EXPORT_USERS`,
`PASSWORD_RESET_LINK_SENT` and `PASSWORD_RESET_LINKS_BULK`.

**ADR-040: Optional password change; a clean HoD export; dropdown values moved with arrows (2026-10-13).**
Decided by the user in one request; where it conflicts with an older rule the new one wins. (1) **A temporary, default or reset
password no longer blocks anything.** The rule of ADR on sessions that answered 403 `PASSWORD_CHANGE_REQUIRED` to every call but
the change itself, and the frontend redirect to a "Choose a new password" page, are removed (`SessionGuardFilter` still ends
sessions of disabled, re-roled or password-changed accounts). The column `must_change_password` is kept (no data lost) and means
"still on a temporary password": `GET /api/auth/me` reports it as `passwordChangeRecommended`, and a banner on every page
recommends a change and can be dismissed for the session; **Account → Change password** is always there, with the usual
password rules. A reset link still sets a new password and no longer leads to another change screen. The standard password
remains refused as a *chosen* password. The cost is that an account on the standard password stays usable by anyone who knows
the e-mail address until its owner changes it; the college network and the banner are the mitigation the user accepted. (2) **The
HoD and administrator appraisal exports were rewritten at the root**: a whole-width merged title and an extra subtitle row (which
made one logical table into three blocks that Excel, Sheets and sorting treated differently) are gone; the workbook is one sheet
with the title in A1, the header in row 2 with an auto-filter, and one row per faculty member; the 15 headings are fixed as
`S.No., Faculty Name, Employee ID, Department, Cadre, Date of Joining, Academics, Certifications, FDPs Attended, FDPs/Guest
Lectures/Workshops Organized, Journals/Conferences/Patents, Contributions/Remarks, Appraisal Status, Submitted Date, HoD Remarks`;
dates are `dd-MMM-yyyy` (real dates in Excel, text of that form in CSV); the CSV is RFC 4180 (CRLF between records, quotes
doubled, a line break inside a value written as "; ", BOM, `text/csv; charset=utf-8`); a missing name or cadre can no longer
break a row. File names are `HOD_Appraisal_Export_<year>_<dept>.xlsx|csv` (`Admin_...` for administrators). (3) **Dropdown order
is changed with Up / Down arrows only**; the free-typed Order number and the `sortOrder` field of the update call are removed.
Each move is saved at once, audited, administrator-only, and the sequence is unique per list (V36 renumbers existing lists in
their current order). Filters and grouped record lists follow the saved sequence instead of sorting alphabetically.

**ADR-041: Cadre workload, course roles, funded-project cap, action plan, role dropdowns (2026-10-13).**
Decided by the user in one request; where it conflicts with an older rule the new one wins. (1) **Faculty see only the criterion,
its maximum and their score** (`B1 · Teaching & Learning — 0 of 40`); the working stays in the API and on the reviewers' score
sheet. (2) **B1 workload** is 20 for every cadre and earns 2.5 / 2.5 / 2.5 / 5 / 10 marks a course (8 / 8 / 8 / 4 / 2 courses a
year), capped at 20 and not rounded; **course file** is 10 with a coordinated course, 4 when every course is as an instructor
(and nothing before a course is entered; a course with no recorded role gets the benefit of the doubt). The user's wording
"award/provide the component maximum" was read as: the component earns its applicable maximum once a course is entered. Both
are **policy version 5**, published like V24, V33 and V35; older versions keep the old rule through a NULL
`courses_per_year`, so no submitted appraisal changes. **Decision to review:** B1's maximum becomes 35 (not 30) for an Associate
Professor and a Professor, because their workload part is now 20 and the parts add up to 35. (3) **ECAP is not called**: the
courses are typed (Course, Course Code), the adapter and its endpoint stay for later; semesters are 1 to 8, enforced in the form
and by a range check even where the list is edited. (4) **Validation is live**: every field is checked as the person types or
leaves it with the server's own wording; Save is held while a touched field is wrong; the server remains the authority.
(5) **Role dropdown + Other**: institute and department roles, and the type and role of an event, are database lists ending in a
pinned "Other" with a typed text beside it; the shown wording is worked out by the server; earlier rows became "Other" with their
old wording. Security, workflow and scoring fields are not given a free-text Other. (6) **Funded projects**: 50m for each sanctioned
project without a limit, 5m for each applied project with the applied ones capped at 10 a year, and the number of projects is no
longer limited (only the marks); per-entry rates are worded `50m for each`. (7) **Section 12, Action Plan for the Next 2
Years**: one text of at most 2,000 words (words, not characters), empty in a draft, required to submit, on the report and as the
last column of the appraisal export (a column added after the 15 the user specified, so their order is unchanged); the
declaration is section 13, "Declaration and Submit". Score & Review remains the reviewers' view-only page and takes no section
number.

**ADR-042: One-time reset of all appraisal data (2026-10-13).**
The user asked for every draft and all appraisal form data to be removed from the live deployment, which cannot be done from the
console (history tables are append-only and the database is not reachable from outside). `FAMS_RESET_APPRAISALS=<token>`
(`StartupAppraisalReset`) removes every appraisal of every status with its form data, scores, messages, review history and issued
reports (rows and files), once per token, in one transaction, using the migration user to drop and re-create the three
"no delete" triggers; accounts, profiles, policies, lists and the audit trail are kept and one `APPRAISALS_RESET` entry records
the counts. No appraisal content is written to the log or the audit trail.

**ADR-043: Form clean-up, FDP rule and permanent deletion of an account (2026-10-13).**
Decided by the user in one request; where it conflicts with an older rule the new one wins. (1) **Teaching & Learning shows the
courses only**: the "Your course requirement" box and the "Institutional roles held" box are gone from the form. The data is
untouched: the roles stay in the database, in the score (their marks are still added to B1) and on the reviewers' score sheet, and
the section still exists on the server; only the display went. (2) **Teaching experience** is required and 0 to 1,000 (the column
is widened to `DECIMAL(5,1)`), enforced at save and listed among the submit blockers, so a draft made earlier cannot be submitted
without it. **No range or limit is printed under a field** any more (no "Between 0 and 1000", no "Within the academic year ...", no
character counter); a wrong value is named in red as it is typed or when the field is left. The word counter of the Action Plan
stays, because it counts, it does not explain. (3) **A journal paper needs a DOI or an ISSN**: at least one value in the field must
be a valid DOI (`10.NNNN/...`, also written `doi:` or as a doi.org address) or a valid ISSN (the check digit is verified); papers
entered earlier are named at submission and refused at the next save of the list. **A conference paper has no citation count**:
the field is removed from the form, validation, display, PDF and export; the column and every value already entered stay in the
database. (4) **A project's amount follows its status** (`conditionalField` with `disabled`): required and a valid non-negative
amount (two decimal places) when sanctioned; greyed out, empty and stored as NULL when applied; an amount sent for an applied
project is refused by the server, not dropped. An amount an applied project already held is left in the database (history is not
rewritten) and is never shown or counted. (5) **Books**: the type reads "Book Chapter" (label change; the code stays `CHAPTER`).
(6) **Outreach**: "External Examiner" is "Project External Examiner" (label; code kept); "Others" is replaced by a pinned "Other"
with a typed role beside it, required only then; stored like the roles of Administration (`role_code`, `role_other`, and `role`
as the wording reported). Older rows keep their wording. (7) **The faculty member is sent no review history**: `history` is not
in their view of an appraisal, there is no panel for it and no reviewer remark reaches them; the history stays for the reviewers
and the administrator (and for the report, whose remarks are still withheld from the faculty member's copy). The Head of the
Department's messages are a different, deliberate channel and are unchanged. (8) **FDP**: each FDP earns 2.5 marks whatever its
length, two earn the whole 5 and more add nothing; the 5-day minimum is gone. The duration is still recorded. B3's maximum is
unchanged (15 = FDPs 5 + certification 10), so no policy version is published and no earlier appraisal changes its meaning: the
marks are worked out on read, as every other mark is.
(9) **Deleting an account** (Admin > Users > Delete permanently, `UserDeletionService`): the administrator sees the person's name,
e-mail address and role and what will be removed and kept, and must type the e-mail address (checked by the server). Refused for
the signed-in administrator's own account and for the only active administrator. Removed: sign-in, faculty record, head-of-department
assignments, reset links and every appraisal never sent for review. What the append-only records depend on cannot go (review history,
the Head of the Department's messages, issued reports, import/restore/hand-over records): the account row then stays as an
anonymous stub (`users.deleted_at`; no e-mail, name, password or employee ID; shown as "Deleted user"), and appraisals that went
through review stay without the person's name, employee ID or contact details. A stub is in no list, count, export, roster or
sign-in, and its appraisals open for nobody; the person's e-mail address and employee ID can be used again. **Decision to review:**
issued official PDF reports are stored files whose checksums are on record and are not rewritten, so they still carry the name as it
was issued. The audit entry (`USER_DELETED`) names the account by number and role with counts, and holds neither e-mail nor name; the
earlier `USER_CREATED` entry, being append-only, still holds the address it was created with.

**ADR-044: Revoke goes one level back with remarks; trail, analytics, PDF bundles and Part A changes (2026-10-14).**
(1) **Revoking is one level back, with remarks, for every reviewer.** The HoD's revoke returns an appraisal to the faculty member
(from any stage before final approval, including one already forwarded, which used to come back to the HoD); the Principal's
or the Director Technical's returns it to the HoD, who reviews it again. This supersedes ADR-035's "no return" for the final
level: the Director Technical still stands at the Principal's level and no status was added (`HOD_REVIEW` is where a Principal
revoke lands). Remarks are written for the level the appraisal goes back to: the faculty member is shown the HoD's, the HoD the
Principal's and Director Technical's, and nobody else. (2) **Each reader is shown only their part of the history**: the HoD's
view drops the Principal's and Director Technical's approval comments, theirs drops the HoD's revoke steps; the printed form
follows (the HoD's copy leaves out the Principal's remarks, the faculty member's leaves out all). The HoD also has a revoke and
resubmit trail per appraisal that works while it is a draft again (it carries no appraisal content). (3) **Bulk PDFs**: the HoD,
Principal, Director Technical and administrator can download the printed forms of the selected appraisals, or all they may open
that year, as one zip of at most 300 (`PdfBundleService`). It calls the same report code as one form, so access and remarks are
unchanged; an administrator may read only submitted appraisals and only through this download, never a draft. (4) **Part A**:
teaching experience is whole years 0-40 and months 0-11 (V39 splits and, above 40 years, caps stored values); research profile IDs
(ORCID, Scopus, Google Scholar, Vidwan) are a list added one at a time, each kind once, replacing the single text box (the old text
stays in the database and is printed only when an appraisal has no profile rows). (5) **FDP**: a program earns 2.5 marks only if
it lasted at least 5 days (`ScoringRules.FDP_MIN_DAYS`), the part is capped at 5 and shorter programs are recorded and earn
nothing. This restores a minimum that ADR-043 had removed. No policy version is published: the marks are worked out on read.
(6) **Admin**: several users' passwords can be reset at once (the standard password, each must change it; the administrator's
own account is never reset). The analytics page drops the users-by-role bars and the repeated charts, and draws a pie of the
accounts grouped by role, status, department or designation and narrowed by filters, from counts only.
