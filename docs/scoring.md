# Scoring

## Maximum marks and per-entry marks

Criteria B1 to B4 have a fixed maximum for each cadre. B5 to B9 are marked **per entry**, the same for every cadre, with
**no upper limit** and no required total (migration `V14__marks_per_entry.sql`; the old "adds up to 100" rule is gone).

| Criterion | Lecturer | Asst Prof | Sr Asst Prof | Assoc Prof | Professor |
|---|---|---|---|---|---|
| B1 Teaching & Learning | 40 | 40 | 35 | 30 | 30 |
| B2 Student Mentoring, Guidance & Achievements | 15 | 15 | 15 | 15 | 15 |
| B3 FDPs / Certifications | 15 | 15 | 15 | 15 | 15 |
| B4 Administrative, Curriculum & Quality Contributions | 15 | 15 | 15 | 15 | 15 |
| B5 to B9 | per entry, no maximum | | | | |

B2, B3 and B4 are **15 for every cadre** (policy version 2, migration `V24__uniform_b2_b3_b4_policy.sql`; the table above is
the current policy). Only B1 still varies by cadre. The first version, from the college's *Cadre_wise* document, is kept
as it was: appraisals started under it keep its maxima and its breakdown.

The B1 to B4 maxima live in the database (`scoring_policy_criteria`) and an administrator changes them under
**Departments, years & scoring** (`POST /api/admin/policies`), which publishes a new version; B5 to B9 are stored as 0
and ignored. The per-entry marks are in code (`ScoringRules`), because they are the college's fixed rate card:

| Criterion | Entry | Marks |
|---|---|---|
| B1 Teaching | workload by the cadre's courses a year, course file by the role, the rest an eighth a course (see "B1: courses" below) | |
| B5 Research & Publications | SCI/SCIE journal paper | 15 |
| | ESCI/Scopus journal paper (UGC-CARE and others earn nothing) | 10 |
| | conference paper | 5 |
| | Ph.D. guided (scholar status Awarded) | 10 |
| | Ph.D. guiding (Registered or Submitted) | 5 |
| | PG guided (M.Tech/MBA Awarded) | 3 |
| | B.Tech project guided (each UG student project) | 2 |
| B6 Funded Projects / Consultancy | project sanctioned (50m for each, no limit) | 50 |
| | project applied for (5m for each; **Max marks: 10** a year, more may be recorded) | 5 |
| B7 Patents, Books & IPR | patent published / granted | 5 / 20 |
| | book published / book chapter | 20 / 5 |
| B8 Outreach | each entry | 5 |
| B9 Memberships, Awards & Recognitions | each entry | 5 |

- **Every mark is calculated, live, and nobody types one** (decision of 2026-10-09, ADR-034). `ScoreService.rows` works out each
  criterion's `score` from the entries whenever an appraisal is read, with the `breakdown` of counts, and the pages refresh it
  after every save. There is no self-score and no endpoint to send one (`appraisal_scores.self_score` was dropped in V28).
- **Only the academic year counts.** Everything is counted only if it was achieved, done or received in the academic year being
  appraised, which runs from 1 June to 31 May (the year's own dates). The form refuses an entry dated outside it
  (`SectionSpec.inAcademicYear`), and the marks also count only entries inside it, so a stray older row earns nothing.
  Dated fields held to it: student achievements (month), events (start and end day), roles
  (from and to day), journal and conference papers and books (month), scholars, funded projects and memberships (year, one of
  the two calendar years the academic year spans), patents (day) and outreach (day). What describes the person (joining dates,
  the year a Ph.D. was registered, research profile metrics) is not.
- **B1: courses (policy version 5 onwards, 2026-10-13).** At most 8 courses can be added and there is no minimum.
  - **Workload & course delivery** is worth 20 for every cadre and is earned by the **cadre's courses**, each worth the
    maximum divided by the courses a year the cadre teaches (`scoring_policies.courses_per_year`), never more than 20:

    | Cadre | Courses a year | a semester | Marks a course |
    |---|---:|---:|---:|
    | Lecturer, Assistant Professor, Senior Assistant Professor | 8 | 4 | 2.5 |
    | Associate Professor | 4 | 2 | 5 |
    | Professor | 2 | 1 | 10 |

    Nobody is made to enter more courses than that; more are recorded and earn nothing extra. Workload is **not** rounded to a
    whole mark any more.
  - **Course file / assessment quality** is worth its whole maximum, **10**, when the faculty member is the **Course
    Coordinator** of at least one course entered, and **4** when every course entered is as a **Course Instructor**. It earns
    nothing until a course is entered. A course saved before roles were asked for has no role and is given the benefit of the
    doubt (10). The component's maximum shown (and printed) is the one that applies.
  - **Innovative / remedial / advanced-learning practices** still earn an eighth of the part for each course.
  - **B1's maximum** is 40 for a Lecturer and an Assistant Professor and 35 for the other cadres (20 + 10 + 5; it was 30 for an
    Associate Professor and a Professor, whose workload part was 15). Appraisals begun under versions 1 to 4 keep the earlier
    rule exactly (an eighth of each part for a course, the workload rounded to a whole mark: a Professor's 15 earns 2, 4, 6, 8, 9,
    11, 13, 15), because `courses_per_year` is empty on those versions. Drafts moved to version 5 with migration `V37`.
  - There is no "student feedback" component (version 3 onwards); the course form still records the Phase-1 and Phase-2 feedback
    percentages, which earn no marks.
- **B2 to B4: a working rate card** (the college has not published one; change it in `ScoringRules`). Each kind of entry has a rate
  and a cap, and the caps add up to the criterion's 15:

| Criterion | Entry | Marks each | Max marks |
|---|---|---|---|
| B2 | students mentored: 6 marks if the total is more than 0, otherwise 0 | 6 once | 6 |
| | student project guided | 2 | 4 |
| | student achievement | 1 | 5 |
| B3 | workshop, FDP, seminar or training of **5 days or more**: one qualifying program earns all 5 | 5 once | 5 |
| | certification (a duration in whole weeks is given): one valid certification earns all 10 | 10 once | 10 |
| B4 | department-level role | 2 | 4 |
| | institute-level role | 1.5 | 3 |
| | event organised or coordinated | 1 | 8 |

## Wording of per-entry rates (2026-10-13)

A rate is worded **`50m for each`** wherever per-entry marks are shown (score panels, the reviewers' score sheet, the report),
never `(50 each)`; a limit is still `(Max marks: N)`. A faculty member's own page shows only
`B1 · Teaching & Learning — 0 of 40`: the criterion, its maximum and what they earned. The components and the working
(`0 courses x 20 / 8 = 0`) are shown to the reviewers only, where they need them to review marks.

## Limits on per-entry criteria, and institutional roles (2026-10-11)

- **How a limit is worded.** Wherever marks are shown (the score sheet, the section score panels, Score & Review, the dashboards,
  the PDF report) a limit reads **`(Max marks: N)`**, with `N` the limit that is configured for that part in the appraisal's
  scoring policy, never a fixed number (the old "(up to N)" is gone). A line that has reached its limit says `Max marks: N`
  after its sum. Ordinary uses of "up to" (a record limit, a date, a file size) are not score limits and are unchanged.

- **Funded projects (B6), changed 2026-10-13 (version 5):** each **sanctioned** project earns **50m for each**, with no limit;
  each **applied** project earns **5m for each** but the applied ones together earn **at most 10 marks a year**
  (`(Max marks: 10)`), and the number of projects that can be recorded is no longer limited (only their marks are). The criterion
  itself has no maximum from version 5 (it was 10 for the whole criterion in version 4, with at most 2 entries).
- **Outreach (B8) and Memberships, awards & recognitions (B9) are limited too** (10 and 5 in policy version 4). The limits are
  **policy data**, not code: the criterion's "maximum" in the scoring policy (0 means no limit), changed by publishing a new
  version under Departments, years & scoring ("Limits for criteria marked per entry"). Appraisals already started keep the limit
  they began with; drafts moved to version 4 with migration `V35`.
- **Institutional roles** (Head of the Department, Associate Dean, Dean ...) are held in Teaching & Learning
  (`institutional-roles`, one entry per role). Each earns the marks set for it in the appraisal's scoring policy
  (`scoring_policy_role_marks`; version 4: HoD 5, Associate Dean 3, Dean 0) and they are added to the B1 marks inside B1's maximum.
  The roles are a dropdown list (Admin > Dropdown lists); their marks are set under Admin > Institutional roles. A role added by an
  administrator earns **0 until its marks are set**.
- **The parts of B1 are editable policy data** (Departments, years & scoring > Change marks > "Made of"): they must add up to the
  maximum. The score sheet and the report show the calculation of the workload part ("3 courses x 15 / 8 = 5.625, to a whole mark").

## Scoring components (criteria B1 to B4)

The college's "Cadre_wise" document, *Scoring criteria B1 to B5 (Annexure A & B)*, gave for each cadre the components
each maximum is made of. B5's are gone with its maximum. B1 had four parts (including "student feedback") in policy versions 1
and 2; **the current version 3 (`V33`) has three** and the totals are unchanged:

| Cadre | Workload & course delivery | course-file/assessment quality | innovative/remedial/advanced-learning practices | B1 |
|---|---|---|---|---|
| Lecturer, Assistant Professor | 20 | 10 | 10 | 40 |
| Senior Assistant Professor | 20 | 10 | 5 | 35 |
| Associate Professor, Professor | 15 | 10 | 5 | 30 |

Appraisals already submitted or approved keep the version they began with, so they still show the four parts of version 2
(history is not rewritten); drafts follow version 3 (`V33` moves them, as `V25` did for version 2).

B2, B3 and B4 have the same parts for every cadre (policy versions 2 and 3). The marks of a component are a **cap on that part
of the criterion**; the marks themselves are calculated from the entries (see the rate card above) and nobody types a score.

| Criterion | Component | Marks |
|---|---|---|
| B2 Student Mentoring, Guidance & Achievements | Mentoring | 6 |
| | Project guidance | 4 |
| | Student achievements | 3 |
| | Academic / placement / competitive-exam support | 2 |
| | **Total** | **15** |
| B3 FDPs / Certifications | FDP / workshops / training | 5 |
| | Certification | 10 |
| | **Total** | **15** |
| B4 Administrative, Curriculum & Quality Contributions | Department responsibilities | 4 |
| | Institute roles | 3 |
| | Curriculum / BoS | 3 |
| | Accreditation works | 5 |
| | **Total** | **15** |

"Quality / accreditation" (3) and "Measurable institutional contribution" (2) are no longer in the current policy; they remain
on version 1 and on the appraisals that were started under it.

**Applying the change.** `V24` publishes version 2 for every cadre of every academic year that is **open** when it runs, copying
B1 and B5 to B9 unchanged. Closed years are left as they were, and a year opened later copies the latest policies of the most
recent year. Submitted and approved appraisals keep the version they began with, as they always do. **Drafts** follow the
new policy: `V25__drafts_follow_current_policy.sql` moves each draft to version 2 and replaces the maxima snapshotted on its
score rows, changing nothing the faculty member entered. A draft stays on version 1 if a self-score saved on it would exceed
a new maximum (none is altered to fit). Each move is an `APPRAISAL_POLICY_MOVED` audit entry.

- **One place.** The components are data: table `scoring_policy_components`, filled by `V9`, changed by `V13`, `V14`, `V24` and `V33`.
  Everything reads them through `ScoreService.rows`: the score sheet, the submit summary, the report.
- **Per cadre, per policy version.** An appraisal shows the components of the policy version it started with.
- **They always add up.** A criterion's components total its maximum. When an administrator publishes a new version, a
  criterion whose maximum is unchanged keeps its components; one whose maximum is changed is published without them.

## Versioning

- `scoring_policies` is unique on (academic year, cadre, version).
- Creating an appraisal picks the highest active version and **copies** the maximum marks into `appraisal_scores`.
- Publishing version 2 affects only appraisals created afterwards. A test proves earlier appraisals keep their marks.

## Maximum and marks

`appraisal_scores` keeps `max_marks` (snapshot of policy) and `review_score`
(reviewer, reserved). The database rejects any score below 0 or above the snapshotted maximum.
