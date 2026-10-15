# User guide

How to use FAMS, by role. Open the address your administrator gave you (for example `https://appraisal.<college domain>`)
and sign in with your college e-mail address. After sign-in you land on the page for your role.

| Role | Lands on | Can |
|---|---|---|
| Faculty | `/faculty` | Fill in and submit their own appraisal; download the report |
| Head of the Department | `/hod` | See the department's progress; review, comment on and approve submitted appraisals |
| Principal | `/principal` | See the whole college; review, comment on and give the final approval |
| Director Technical | `/director` | The same as the Principal: see the whole college; review, comment on and give the final approval |
| Administrator | `/admin` | Manage accounts, departments, academic years and scoring; read the audit trail |

## Everyone

### Signing in
- Your **e-mail address is your user name**. There is no self-registration: an administrator creates your account.
- Your old password is the **standard password** the administrator gives you (the same for everyone). You can start working at once; a banner recommends
  choosing your own password, and **Account → Change password** is available at any time. Nothing is blocked until you do.
- **Password rules:** at least 10 characters, with a letter and a number; no leading or trailing space; at least five
  different characters; not containing your e-mail name; not a common password or the college's name with digits added
  (`Password@2026`, `Svec@123456`). The page shows hints as you type.
- **Wrong passwords never lock you out.** You can try again at once. If you have forgotten yours, ask an administrator to reset it.
- You are signed out after **30 minutes of inactivity**.
- **Forgotten password:** ask an administrator to reset it. They set it back to the standard password and you choose your own at
  next sign-in. There is no "forgot password" link and no e-mail is sent.

### Changing your password
*Account -> Change password*. You need your current password. Changing it signs you out of every other browser.

## Faculty

### 1. Start your appraisal
On the dashboard choose **Start your appraisal**. It is created for the current academic year (one per year) with Part A
pre-filled from your profile and your cadre's maximum marks copied in. Those maxima stay fixed for this appraisal even if
the college publishes new ones later.

### 2. Fill in the form
The form has eleven pages that follow the printed form. Open them from the section navigation.

| Page | Form reference | Content |
|---|---|---|
| 01 General Information | Part A | Your identity block (read-only, from your profile) and the details you can correct |
| 02 Teaching & Learning | Part B 1 | Courses handled (course code, your role in it, section, semester, hours a week from 1 to 6, pass %, feedback %); totals are computed |
| 03 Mentoring & Projects | Part B 2 | Students mentored, student achievements, projects guided |
| 04 FDPs & Certifications | Part B 3 | Workshops, FDPs, seminars, training (duration in days; each earns 2.5 marks and two earn the full 5); certifications (duration in whole weeks; name the platform when it is Other) |
| 05 Administration | Part B 4 | Institute-level and department-level roles; events organised |
| 06 Research & Publications | Part B 5 | Journals, conference papers, research metrics, scholars, Ph.D. progress |
| 07 Funded Projects / Consultancy | Part B 6 | Sanctioned and applied projects |
| 08 Patents, Books & IPR | Part B 7 | Patents, books and chapters |
| 09 Outreach | Part B 8 | Outreach activities by role |
| 10 Memberships, Awards & Recognitions | Part B 9 | Memberships and awards |
| 11 Other Contributions | Part B 10 | Anything else |
| 12 Action Plan for the Next 2 Years | Part B 11 | One text of at most 2,000 words, **required before you can submit** (a draft may leave it empty) |
| 13 Declaration and Submit | | The declaration and the submit button |

How the pages work:
- **Autosave.** Changes are saved as you go; the save status shows near the top. You can leave and come back.
- **Tables:** *Add*, edit, duplicate or delete rows. A row is added through a dialog and is saved **only when it is
  complete and valid**; a half-filled row stays in your browser until you finish it.
- **Validation** is the server's. A rejected save changes nothing and the message names the field. Dates must not end
  before they start; months are `YYYY-MM`; percentages are 0 to 100.
- **Totals** the form asks for (teaching load, average pass %, counts by indexing, amounts sanctioned, and so on) are
  worked out from your rows. You do not type them.
- **Two browser tabs:** do not edit the same appraisal in two tabs. A save replaces a section's whole list, so the later
  save wins and can remove a row the other tab added.

### 3. Your marks
A limit on a part is written **(Max marks: N)**: that part can never earn more than N, however many entries you add.
Nobody types a mark. Each page of the form that earns marks shows them as you fill it in, worked out from your entries by the
rates the college has set, and the same marks go on the printed form. There is **no separate score page** for faculty: after
the last section you go straight to **Submit**.

### 4. Submit
While anything is still missing, a box at the top of every page lists it by section, for example *Teaching & Learning: 2 more
courses needed (6 of 8 added)*, with a link to each field (a missing Part A detail, the *Add a course* button, a score on the
score sheet). A link takes you to the page and puts the cursor in the field. The box follows the server's own check, so it
gets shorter as you save and disappears when nothing is missing.

On the submit panel tick **I make this declaration** and choose **Submit to your HoD**. The server
records the date. **Submission is final: nothing is returned for correction, and the appraisal becomes read-only for
everyone.** Check every page first.

### 5. After submission
The dashboard shows where the appraisal is and the history (the steps taken; the comments reviewers write when they approve
are not shown to you).

**A message from your Head of the Department.** If your HoD has a query about your appraisal they can send you a message
while they review it. It appears at the top of your dashboard and on the Submit page, marked *New* the first time
you see it, and asks you to arrange to meet them. If the HoD corrects the wording it appears again as *Updated*, with the
time it was edited. Your appraisal stays with the HoD and stays locked; there is nothing to
resubmit. After you have met, the HoD carries on with the review.

| Status | Meaning |
|---|---|
| `DRAFT` | You are still filling it in |
| `SUBMITTED` | Waiting for your HoD to begin |
| `HOD_REVIEW` | Your HoD is reviewing |
| `HOD_APPROVED` | Forwarded; waiting for the Principal or the Director Technical |
| `PRINCIPAL_REVIEW` | The Principal or the Director Technical is reviewing |
| `APPROVED` | Final. The official report is available |

### 6. The report (PDF)
*The printed form* downloads a PDF that mirrors the six-page form. Until final approval it carries a **DRAFT**
watermark and is generated on request. After final approval the **official** copy is generated once, stored with its
checksum and served unchanged. It has printed signature lines, not digital signatures.

## Head of the Department

**Department console** (`/hod`) shows, for the departments you are assigned to, how many faculty are at each stage:
*not yet submitted*, *with the HoD*, *with the Principal / Director*, *approved*, plus the full roster. A banner says how many are
waiting for you. A faculty draft appears as "not yet submitted" and **cannot be opened**.

**Department review** (`/review`) is the queue. Open an appraisal to see all of it, read-only: every form page, the
score sheet and report.

1. **Begin review** (moves it to `HOD_REVIEW`).
2. Optionally write a comment (up to 2000 characters). It is printed on the report as **Recommendations of HoD**.
3. **Approve.** This forwards the appraisal to the Principal and the Director Technical. You cannot send it back.

**Not ready to approve?** Open **Message the faculty member** on the same page (it is there once you have begun the review
and until you approve). Write what you need, or press *Use a standard request to meet*, and send it. The faculty member
sees it on their dashboard and is asked to meet you; you see whether they have seen it. Until you approve, you can press
**Edit** under a message you wrote to correct it: the faculty member is shown it again as an update, marked as edited, and
the earlier wording is kept and shown to you under the message (*Earlier wording*), but not to the faculty member. You
cannot delete a message or edit a colleague's.

As soon as you send a message the appraisal is shown as **Query raised** (in your lists, on the appraisal page and on the
faculty member's dashboard), and it moves from *Needs your action* to *Awaiting the faculty member*, because the next step is
theirs: to meet you. It is still with you and still locked; after you have met, approve it as usual. A message does not change the appraisal
or send it back, and it is separate from the comment you write with your approval (which they cannot see). Only you and
the faculty member can read it.

### Downloading appraisal details (HoD)
On your console, the Faculty list has a checkbox on every row and one in the header (everything shown after your filters). Tick
the faculty you want; a bar says how many are chosen and offers **Download Excel (.xlsx)** and **Download CSV**. Your choice is kept
while you search, filter or sort. You can export only faculty of your own departments, the server checks it again, and every
export is recorded in the audit trail. The columns are those of the administrator's export (see "Appraisal export").

**What the files look like.** The Excel file is one worksheet: a title line (year and department), one header row with filter
buttons, and one row per faculty member, each value under its own heading, with dates as `dd-MMM-yyyy`, long text wrapped and the
header kept in view as you scroll. The CSV file is plain UTF-8 (it opens in Excel; a few older programs need "Import text"):
one header row and one line per faculty member, with several records in one cell separated by "; ". File names are like
`HOD_Appraisal_Export_2025-26_CSE.xlsx` and `.csv`. A dash "—" means there is nothing to show (not submitted, or nothing entered).

**Revoke** takes an appraisal back at any stage before the Principal's or Director Technical's final approval, even one you have
already forwarded. Use the *Revoke this appraisal* button under the review box and give your remarks (they are required). The
appraisal goes back to the faculty member as a draft to correct and send again, and **they are shown your remarks**; the
Principal and the Director Technical are not. If the Principal or the Director Technical revokes one you forwarded, it comes back
to you with their remarks on the appraisal page; you can review it again and forward it, or revoke it to the faculty member.
Every revocation is recorded with who, the time, the remarks and the statuses. A finally approved appraisal cannot be revoked.

**Revoked and sent again.** Each faculty member's row on your console shows how many times the appraisal has been revoked and
sent again, with *View trail* to read every step and what was written. It works even while the appraisal is a draft with the
faculty member. The *Status* column reads *Submitted to Principal / Technical Director* once you have forwarded it, and the column
after *Submitted* gives the date you did.

**Download PDFs.** Tick faculty and choose *Download PDFs (zip)*, or choose *Download all PDFs (zip)* for every submitted
appraisal of the year in your department. The Principal and the Director Technical have the same on their console and review
queue, and the administrator on the *Appraisal export* page. A draft is private and never included. Your copy of a form leaves out
what the Principal and the Director Technical wrote with their approval.

If another HoD of the same department starts at the same moment, only one of you succeeds and the other sees a conflict
message; refresh and carry on. If the console says *No department assigned*, ask an administrator.

## Principal

**Console** (`/principal`) shows counts per department and in total, and the ten appraisals that have waited longest.
**Before the HoD has approved an appraisal you see only a count** ("not yet with you"), never a name.

**Review** (`/review`): open an appraisal, **Begin review** (`PRINCIPAL_REVIEW`), optionally comment (printed as
**Remarks of the Principal**), then **Approve**. **Your approval is final**: the appraisal becomes `APPROVED`, read-only
for good, and the official report is generated on first request.

## Director Technical

The Director Technical works exactly as the Principal does, from **Console** (`/director`) and **Review** (`/review`), and
sees the same appraisals. Whichever of the two begins the review of an appraisal, either may finish it; the first to
**Approve** gives the final approval. A comment is printed as **Remarks of the Director Technical** and the history
records that the Director Technical acted. Every report carries a box for the Principal and one for the Director
Technical; the one who did not act on that appraisal is left empty.

## Things that changed on 2026-10-11

- **Courses** are typed: **Course** and **Course Code** are two ordinary fields (ECAP is not used for now). Semester is 1 to 8
  for every department. The page tells you how many courses your cadre teaches (for example 4 a semester, 8 a year) and what each
  earns; you are never made to enter more than that.
- **Institutional roles** (Head of the Department, Associate Dean ...) are listed on the Teaching & Learning page and earn the marks set
  for them within the page's maximum.
- **Funded projects**: a sanctioned project earns 50m for each; an applied one 5m for each, and the applied ones together earn at
  most 10m a year. You can record as many as you have. **Outreach** and **Memberships** also have limits.
- **ISBN** is required for every book and book chapter (ISBN-10 or ISBN-13, with or without hyphens).
- There is no separate score page for faculty: the last page is **13 Declaration and Submit**. Your marks still go on the printed form,
  and each page shows only its criterion, what it is worth and what you have earned (`B1 · Teaching & Learning — 0 of 40`).
- **Signing out**: after 30 minutes without activity you are signed out; two minutes before, you are asked whether to stay signed
  in. What you had typed into an open form is kept in the browser tab and offered again after you sign in.
- **Forgot your password?** Use the link on the sign-in page; you are e-mailed a one-time link to choose your own password.
- **Your HoD can revoke** your appraisal before final approval. It comes back to you as a draft, marked *Revoked*, with your HoD's
  remarks at the top of the page. Correct it as asked and submit it again. Every earlier revocation stays listed on your page.

## Administrator

Administrators configure the system. They **cannot open or change an appraisal**; the console shows counts by stage, set-up
checks and audit entries (with file names and checksums of appraisal entries left out). Since 2026-10-12 they can **export the
appraisal details** of chosen faculty (see "Appraisal export" below); every export is recorded in the audit trail.

### Choosing several users (`/admin/users`)
Every row has a checkbox, and the one in the header chooses everything the list shows after your filters. The choice is kept
while you search, filter or sort (the bar says how many chosen users the filters are hiding), and the bar of actions appears only
once a user is chosen:

- **Send password reset link**: each chosen active user is e-mailed their own one-time link, valid for 30 minutes, to choose a new
  password. No password is created, shown or e-mailed, and the current password keeps working until the link is used. You see how
  many were **sent**, **skipped** (disabled or closed users, yourself, someone sent several links a moment ago) and **failed**
  (the mail server did not accept the message, or the server has no mail set up), with the reason for each. It needs
  `FAMS_MAIL_*` and `FAMS_PUBLIC_URL`; without them nothing is issued. Your own password is changed under Account.
- **Download selected users CSV / Excel**: name, employee ID, e-mail, role, status, department, designation, last sign-in and
  whether they have chosen their own password. Nothing secret is in the file.

The old "reset the passwords of these users" action (which set everyone back to the standard password and showed it) is gone; a
single user's **Reset password** on their own row is unchanged.

### Appraisal export (`/admin/exports`)
Choose the academic year, narrow by department, designation and appraisal status, tick the faculty and download **Excel (.xlsx)**
or **CSV**. The file has one row per faculty member with these columns, in this order: S.No., Faculty Name, Employee ID, Department, Cadre,
Date of Joining, Academics (courses, students mentored, projects, achievements), Certifications, FDPs Attended,
FDPs/Guest Lectures/Workshops Organized, Journals/Conferences/Patents (also books, funded projects and research scholars),
Contributions/Remarks (institutional and administrative roles, outreach, memberships and awards), Appraisal Status, Submitted Date
and HoD Remarks. Several records in one Excel cell are a numbered list (one per line; in CSV they are joined by "; "); an empty
cell shows "—". Names are `Admin_Appraisal_Export_<year>_<departments>.xlsx` / `.csv`. A faculty member who has not submitted is
listed as "Not submitted" with "—" everywhere else: **a draft is private to its author and is never exported.**

### Console (`/admin`)
Shows account counts, **set-up checks**, appraisals by stage and the latest audit entries. Fix anything red: faculty and
reviewers are blocked until you do. The checks cover an open academic year, a scoring policy for every cadre, an HoD for
every department that has faculty, a Principal or Director Technical and a second administrator.

### Users (`/admin/users`)
- **Create** a faculty, HoD, Principal, Director Technical or administrator account. It starts with the **standard password**
  (`Srivasavi@123`), which the person must replace when they first sign in; the dialog shows it so you can pass it on.
- **Add accounts from a file** creates many at once. Choose *Add accounts from a file*, download the empty file, fill in
  one row per person and upload it. The columns are **Name, College e-mail address, Role, Employee ID, Contact number,
  Department, Designation (cadre)**:
  - *Role* is Faculty, HoD, Principal, Director Technical or Administrator. Faculty rows need an employee ID, one department and a
    designation; HoD rows need a department (several: `CSE; ECE`); Principal, Director Technical and Administrator rows need only a name,
    an e-mail address and the role. Contact number is always optional.
  - *Department* is the code (`CSE`) or the name; *Designation* is Lecturer, Assistant Professor, Senior Assistant
    Professor, Associate Professor or Professor (`Asst. Prof.` and the like are understood).
  - Save the spreadsheet as **CSV** (UTF-8 if you have the choice). Format the contact-number column as Text, or
    Excel shortens long numbers to `9.88E+09` and the row is refused.
  - **All or nothing:** every row is checked first. If any row is wrong nothing is created, and you are shown each
    problem with its line number; correct the file and upload it again. Up to 1000 accounts at a time.
- **Reset password** sets the password back to the standard one; the person must change it at next sign-in.
- **Reset the passwords of these accounts** (above the list) does the same for every account the list shows with its filters applied, in one step: filter first (for example by department or "Has not chosen a password"), read the count, confirm. Your own account and closed accounts are left out, everyone reset is signed out everywhere, and each must choose a new password at their next sign-in. Until they do, anyone who knows the standard password and their e-mail address can sign in as them, so reset only accounts whose owners are about to sign in.
- **Disable** an account; the person's sessions end immediately.
- **Edit** details (the form sends only the fields you change). **A role never changes**: create a new account instead.
- You **cannot disable or reset your own account** (use *Account -> Change password*).
- Both levels of the approval chain need an active account, or appraisals stop there.

### Departments, years & scoring (`/admin/setup`)
- **Departments:** add, rename or close. The code never changes.
- **Academic years:** open a year (the latest policy of each cadre is copied in as version 1 so appraisals can start at
  once); close or reopen one. New appraisals go into the most recent year marked active.
- **Scoring policy:** publish a new version for a cadre. The nine criteria are whole numbers that **add up to exactly
  100**. A new version affects only appraisals created afterwards. If you change a criterion's maximum its scoring
  components are not carried over (the editor warns you first), because they would no longer add up.

### Analytics (`/admin/analytics`)
Charts and figures for one academic year (choose it at the top): appraisals by status, how many have been submitted and approved,
and each department **separately** (faculty, Heads of Department, started, submitted, approved, the rates). It shows counts only;
no name or appraisal content appears. The Overview also shows a card for every department.

### Institutional roles (`/admin/roles`)
The marks each institutional role (Head of the Department, Associate Dean, ...) earns in Teaching & Learning for an academic year.
Add or rename a role under Dropdown lists; a role you add earns 0 until you set its marks here.

### Changing a user's role
On the Users page choose **Change role** for an active user (not yourself), for example to make someone an administrator. A Head of
the Department also needs the departments they head. Their sessions end so the new role applies at their next sign-in; the change
is in the audit trail (`USER_ROLE_CHANGED`).

### Forgotten passwords
People can ask for a link themselves on the sign-in page ("Send me a reset link"): it is e-mailed, works once and expires in 30
minutes, and they choose their own password with it. It needs the mail settings (`FAMS_MAIL_*`) and `FAMS_PUBLIC_URL`; without them
nothing is sent and you can still reset a password on the Users page.

### Dropdown lists (`/admin/lists`)
The choices offered on the appraisal form (program, branch, section, semester, hours a week, course role, platforms, levels,
statuses, outreach roles, and the suggested words for roles and awarding bodies) are kept here, not in the program.
- Pick a list on the left. **Add a value** (a branch asks which program it belongs to); it is offered to everyone at once.
- **Rename** a value (the wording on the form, and optionally different wording on the printed report), change its
  **sequence** with the **↑ / ↓** arrows (one place earlier or later; it is saved at once and every form, filter, report and
  export follows it; the first value cannot go up and the last cannot go down; a switched-off value keeps its place and is stepped
  over), and **switch it off** when it should no longer be offered. Nothing is ever deleted: appraisals that already hold a switched-off
  value keep it and its wording, and can be saved again unchanged.
- A value marked **used by rules** (for example SCI/SCIE and Scopus journals, sanctioned projects, granted patents) can be renamed
  but not switched off, because marks are worked out from it. A value you add to such a list is recorded but earns no marks.
- Lists of numbers (semester, hours a week, author position) take whole numbers only; the form's allowed range follows the list.
- Every change is written to the audit trail (`REFERENCE_VALUE_ADDED`, `REFERENCE_VALUE_UPDATED`).

### Audit trail (`/admin/audit`)
Every significant action, newest first, up to 100 per page. Filter by action, entity type and entity ID. It cannot be
edited or deleted. Watch for `PASSWORD_RESET` entries: a reset lets you become that person, and it is recorded.

### First-time installation
Set `FAMS_BOOTSTRAP_ADMIN_EMAIL` and `FAMS_BOOTSTRAP_ADMIN_PASSWORD` once (used only while no administrator exists),
sign in, change the password, create the real accounts, then remove both variables. See [deployment.md](deployment.md).

## Troubleshooting

| You see | Meaning and what to do |
|---|---|
| "Your password must be changed before you continue" | Your password was issued by an administrator. Choose a new one |
| Signed out suddenly | 30 minutes idle, or an administrator disabled, re-roled or reset your account, or you changed your password elsewhere |
| 404 on an appraisal | It does not exist **or you may not see it**; the system does not say which |
| "Not saved" with a field named | That value breaks a rule (a limit, a date order, a format). Fix the field and save again |
| Conflict (409) on review | Another reviewer acted first. Refresh |
| Report says DRAFT | The appraisal is not yet finally approved |

## Things that changed on 2026-10-13

- **Course workload** follows your cadre: Lecturer, Assistant Professor and Senior Assistant Professor 4 courses a semester (2.5 marks
  each), Associate Professor 2 (5 marks), Professor 1 (10 marks); the workload part is at most 20. The **course file** is worth 10 if you
  are the Course Coordinator of at least one course you entered, and 4 if you are only a Course Instructor.
- **Courses are typed again** (Course, Course Code); **semesters are 1 to 8**.
- **Mistakes show as you type.** A field is checked as you type or leave it (for example *Pass percentage must be between 0 and 100.*),
  Save stays off while a field you touched is wrong, and nothing you typed is erased.
- **Institute-level and department-level roles** are chosen from a list that ends in **Other**; choose Other to type your own. The
  type and role of an **event** work the same way.
- **Action Plan for the Next 2 Years** (section 12) is new and needed before you can submit; the declaration is now section 13.
- **Funded projects**: 50m for each sanctioned, 5m for each applied (at most 10m for the applied ones together).

### Second set of changes on 2026-10-13

- **Teaching & Learning** shows only your courses. The box with your course requirement and the "Institutional roles held" box are
  gone from the form (what was entered earlier is still counted).
- **Teaching experience (years)** in General Information is required and must be between 0 and 1000. Nothing is printed under a
  field to say what range it takes; if you type something wrong the message appears in red at once.
- **Journal publications**: the **DOI or ISSN** is required, and must include a valid DOI (for example `10.1000/xyz123`) or ISSN
  (for example `0378-5955`). **Conference papers** no longer ask for the number of citations.
- **Funded projects**: **Amount** is for a **Sanctioned** project, where it is required (0 or more, up to two decimals). Choosing
  **Applied** greys it out and clears it.
- **Books**: the type is **Book** or **Book Chapter**.
- **Outreach**: "External Examiner" is now **Project External Examiner**. Choose **Other** to type your own role in the box that
  appears (it is required only then).
- **No review history for you.** Where your appraisal stands is shown on your page and on Declaration and Submit, and the Head of
  the Department's messages are still shown to you; the list of steps and the reviewers' remarks are for the reviewers.
- **FDPs**: a Workshop / FDP / Seminar / Training program of **five days or more** earns **2.5 marks**. The total is limited to 5
  marks, however many are entered; a program of fewer than five days is recorded but earns no marks.
- **Teaching experience** is given as whole years (0 to 40) and whole months (0 to 11).
- **Research profile IDs**: choose ORCID, Scopus, Google Scholar or Vidwan, enter the ID or link and add them one at a time; each
  can be added once.

### Delete a user permanently (administrators)

On **Users**, **Delete permanently** removes an account for good. You are shown the person's name, e-mail address and role, what
will be removed (their sign-in, faculty record, head-of-department assignments, reset links and any appraisal never sent for
review) and what is kept without the person (appraisals that went through review, and the history and messages that name the
account, shown as "Deleted user"). You must **type the person's e-mail address** before the button works. You cannot delete the
account you are signed in with, or the only active administrator. The person is signed out at once, disappears from every list,
export and count, and their e-mail address and employee ID can be used again. It cannot be undone. If a Head of the Department's
department is left with nobody, the warning says so: assign another before submitted appraisals there need review. The audit trail
records who deleted which account (by number and role), without the address or name.
