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
- Your old password is the **standard password** the administrator gives you (the same for everyone). At first sign-in you are taken straight to
  *Choose a new password* and can do nothing else until you have.
- **Password rules:** at least 10 characters, with a letter and a number; no leading or trailing space; at least five
  different characters; not containing your e-mail name; not a common password or the college's name with digits added
  (`Password@2026`, `Svec@123456`). The page shows hints as you type.
- **Too many wrong passwords** (5 for your account from one machine in 10 minutes) locks sign-in from that machine for
  ten minutes. Wait, then try again.
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
| 02 Teaching & Learning | Part B 1 | Courses handled, pass %, student feedback; totals are computed |
| 03 Mentoring & Projects | Part B 2 | Students mentored, student achievements, projects guided |
| 04 FDPs & Certifications | Part B 3 | Workshops, FDPs, seminars, training; certifications |
| 05 Administration | Part B 4 | Institute-level and department-level roles; events organised |
| 06 Research & Publications | Part B 5 | Journals, conference papers, research metrics, scholars, Ph.D. progress |
| 07 Funded Projects / Consultancy | Part B 6 | Sanctioned and applied projects |
| 08 Patents, Books & IPR | Part B 7 | Patents, books and chapters |
| 09 Outreach | Part B 8 | Outreach activities by role |
| 10 Memberships, Awards & Recognitions | Part B 9 | Memberships and awards |
| 11 Other Contributions | Part B 10 | Anything else |

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

### 3. Score sheet (self-scores)
Enter **one self-score per criterion**, from 0 up to your cadre's maximum (at most two decimals). The sheet shows the
maximum and the components it is made of, from the college's cadre-wise document (B1 to B5). Notes:
- A criterion whose maximum is 0 for your cadre (a Lecturer's *Funded Projects* and *Patents*) shows **n/a**.
- Leaving a score empty means "not entered". Scores are optional for now; the submit panel lists missing ones but does
  not block you.
- Nothing is calculated for you. The components explain what the maximum is made of; you decide the score.
- The total shown adds what you have entered so far.

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
while they review it. It appears at the top of your dashboard and on the Score & Review page, marked *New* the first time
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

## Administrator

Administrators configure the system. They **cannot read appraisal content**; they see counts by stage, set-up checks and
audit entries (with file names and checksums of appraisal entries left out).

### Console (`/admin`)
Shows account counts, **set-up checks**, appraisals by stage and the latest audit entries. Fix anything red: faculty and
reviewers are blocked until you do. The checks cover an open academic year, a scoring policy for every cadre, an HoD for
every department that has faculty, a Principal or Director Technical and a second administrator.

### Accounts (`/admin/users`)
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
| 429 / "too many attempts" | Sign-in limit reached. Wait ten minutes, or ask an administrator |
| Conflict (409) on review | Another reviewer acted first. Refresh |
| Report says DRAFT | The appraisal is not yet finally approved |
