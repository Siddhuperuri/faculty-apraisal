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
| B1 Teaching | each course handled (up to the workload component's 20; 8 courses reach it) | 2.5 |
| B5 Research & Publications | SCI/SCIE journal paper | 15 |
| | ESCI/Scopus journal paper (UGC-CARE and others earn nothing) | 10 |
| | conference paper | 5 |
| | Ph.D. guided (scholar status Awarded) | 10 |
| | Ph.D. guiding (Registered or Submitted) | 5 |
| | PG guided (M.Tech/MBA Awarded) | 3 |
| | B.Tech project guided (each UG student project) | 2 |
| B6 Funded Projects / Consultancy | project sanctioned | 50 |
| | project applied for | 5 |
| B7 Patents, Books & IPR | patent published / granted | 5 / 20 |
| | book published / book chapter | 20 / 5 |
| B8 Outreach | each entry | 5 |
| B9 Memberships, Awards & Recognitions | each entry | 5 |

- **Calculated live, adjustable.** The server works the marks out from the entries (`ScoreService.rows`: `calculated`,
  with the `breakdown` of counts) and each criterion's page shows them, refreshed after every save. The faculty member
  may type their own score over a calculated one (`selfScore`); the mark that counts (`score`) is the typed score if
  there is one, otherwise the calculation. Clearing the typed score returns to the calculation.
- **B2 to B4, and B1 apart from its workload, have no per-entry marks** (the college has not given any): they are typed
  by the faculty member, from 0 up to the cadre's maximum. B1's calculated marks are only its workload part.
- **The 8-course rule.** A faculty member teaches 4 courses in each of the academic year's 2 semesters and cannot submit with fewer than 8 courses (`ScoreService.MIN_COURSES`).
- A criterion's `maxMarks` is `null` in the API for B5 to B9; the score sheet, the printed report and Annexure A show
  "per entry" and total only B1 to B4.

## Scoring components (criteria B1 to B4)

The college's "Cadre_wise" document, *Scoring criteria B1 to B5 (Annexure A & B)*, gave for each cadre the components
each maximum is made of. B5's are gone with its maximum, and B1 now has the same four parts for every cadre:

| Cadre | Workload & course delivery | student feedback | course-file/assessment quality | innovative/remedial/advanced-learning practices | B1 |
|---|---|---|---|---|---|
| Lecturer, Assistant Professor | 20 | 8 | 6 | 6 | 40 |
| Senior Assistant Professor | 20 | 6 | 5 | 4 | 35 |
| Associate Professor, Professor | 20 | 4 | 3 | 3 | 30 |

B2, B3 and B4 have the same parts for every cadre (policy version 2). The marks of a component are a **cap on that part of the
criterion**, not a rate per entry: nothing is calculated from the number of entries, and the faculty member still types
the criterion's score, from 0 up to its 15.

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

- **One place.** The components are data: table `scoring_policy_components`, filled by `V9`, changed by `V13`, `V14` and `V24`.
  Everything reads them through `ScoreService.rows`: the score sheet, the submit summary, the report.
- **Per cadre, per policy version.** An appraisal shows the components of the policy version it started with.
- **They always add up.** A criterion's components total its maximum. When an administrator publishes a new version, a
  criterion whose maximum is unchanged keeps its components; one whose maximum is changed is published without them.

## Versioning

- `scoring_policies` is unique on (academic year, cadre, version).
- Creating an appraisal picks the highest active version and **copies** the maximum marks into `appraisal_scores`.
- Publishing version 2 affects only appraisals created afterwards. A test proves earlier appraisals keep their marks.

## Three separate concepts

`appraisal_scores` keeps them apart: `max_marks` (snapshot of policy), `self_score` (faculty) and `review_score`
(reviewer, reserved). The database rejects any score below 0 or above the snapshotted maximum.

## Self-score entry

`PUT /api/appraisals/{id}/scores` (owner, while the appraisal is editable). The faculty member types a score per
criterion; the server checks only what the form states:

- a number from 0 up to that cadre's maximum (the snapshot in `appraisal_scores.max_marks`), at most 2 decimal places;
- a criterion whose maximum is 0 for the cadre (for example a Lecturer's Funded Projects and Patents) accepts only 0, and
  the UI shows it as "n/a";
- empty means "not entered" (null), and entering is optional for now;
- one invalid value rejects the whole request, so a save never half-applies; criteria not sent are left alone;
- locked after submission like every section, reopened if returned; only the author may write.

A save that changes nothing writes nothing and adds no audit entry (`SCORES_SAVED` is recorded only for real changes).
The criteria names and their order come from `scoring/Criteria.java` (the form's wording); a test checks every criterion
code seeded in the policy is listed there. The total on screen adds the valid scores entered so far; it is not stored.
The submit panel lists criteria with no self-score as a **non-blocking** note.

## Not decided (not invented)

The form does not say how activities become marks. `calculation_mode` is `SELF_ENTERED` for every criterion, meaning
the faculty types a score and the system only validates the range. If the institution supplies formulas they become
versioned rules (`FORMULA` plus `configuration_json`), never code constants. See `docs/requirements.md` C15-C21.

Where the form itself asks for a total or an average (for example teaching load and average pass percentage), those
will be computed from the records; that is arithmetic on entered data, not a scoring rule.
