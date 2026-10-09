# Requirements: assumptions and open questions

## Checked against the official form

The college's six-page form (supplied by the Vice HoD) and srivasaviengg.ac.in were compared with the build:

- **Matches:** every Part A field, Part B items 1-10, the nine criteria of the score sheet, the Annexure A maxima (all
  five cadres, 45 values), the supporting-document list, declaration, HoD recommendations and Principal remarks.
- **Added after reading it:** Part A stored per appraisal (snapshot of the profile, editable while a draft); the
  declaration with place and date captured at submission; the printed counts and amounts (journals by indexing,
  projects sanctioned / applied, patents filed / published / granted, books vs chapters) computed from the rows; the
  outreach role as the form's closed list of eleven roles; the college's own programme names for departments.
- **Form wording used in the UI:** "Recommendations of HoD" and "Remarks of the Principal" are the comment boxes of the
  review panel. Institute-level and department-level roles are two tables over one section.
- **The form says** the same criteria and maxima are used by "the Appraisal Panel in the Faculty Performance Evaluation
  Form". That is a separate panel form, not built; `appraisal_scores.review_score` is reserved for it.
- **Not on the form, so not invented:** how activities become marks, what is mandatory, evidence rules.


The schematic (section 4) lists 37 questions that must be answered by the Vice HoD or another authorised stakeholder.
Where code needed a decision, the **working assumption** below was implemented and kept easy to change.
Nothing here is an institutional rule until confirmed.

## Assumptions implemented

| # | Question | Assumption in code | Where to change |
|---|---|---|---|
| B6 | One appraisal per faculty per year? | Yes. `UNIQUE (faculty_id, academic_year_id)` | `V1__core_schema.sql` |
| B8 | Does submission lock editing? | Yes, for good. Only a DRAFT is editable | `AppraisalStatus.isEditableByFaculty` |
| B9/B10 | HoD and Principal can return? | No. Nothing is returned for correction: the HoD may comment and approves (which forwards it), the Principal approves | `AppraisalStatus`, `WorkflowAction` |
| B12 | HoD approval mandatory before the Principal? | Yes. The chain is HoD, then Principal, neither skippable; the Principal sees an appraisal only once the HoD has approved it | `AppraisalAccess.principalVisible`, `AppraisalStatus.allowedNext` |
| (new) | Which scoring components apply? | Those of the college's cadre-wise document for criteria B1 to B5, per cadre, exactly as written; the other four criteria have none. Self-scores stay one per criterion | `V9__scoring_components.sql`, `ScoreService.rows` (`docs/scoring.md`) |
| B14 | Final approval irreversible? | Yes. `APPROVED` is terminal and locked | `AppraisalStatus` |
| (new) | May an HoD see a faculty draft? | No, only after submission | `AppraisalAccess.load` |
| (new) | May an Admin read appraisal content? | No. Admin manages configuration, not appraisal content | `AppraisalAccess.load` |
| A5 | Can one person hold several roles? | No, one role per user | `users.role` |
| C18 | Does the system calculate scores? | No. Marks are self-entered and validated against the maximum | `scoring_policy_criteria.calculation_mode` |
| F34-36 | Scope | All eight departments from the form are seeded; departments are data, not code | `V2__seed_reference_data.sql` |
| C15-C21 | Self-score entry: required? calculated? | Typed by the faculty member and optional; the server only enforces 0 to the cadre maximum (2 decimals). Nothing is calculated, suggested or required at submission. The HoD cannot change a self-score; the Appraisal Panel's separate score (`review_score`) is not built | `ScoreService` |
| (new) | Teaching summary: how are the averages computed? | Simple mean of the entered rows; feedback average is the mean of every Phase-1 and Phase-2 value entered. Total load is the sum of hours per week | `TeachingSummary` |
| (new) | Which fields are required? | A field is required when its database column is NOT NULL. Feedback percentages, optional details (DOI, venue...) are optional. Required-ness at *submission* is not decided yet | `Sections.java` |
| (new) | Limits on numbers (e.g. students, citations, hours/week <= 168) | Generous sanity bounds only, not institutional rules | `Sections.java` |
| (new) | Funded-project figures: what are "Total Amount Sanctioned" and "Consultancy Revenue"? | Sum of sanctioned *research* projects, and sum of sanctioned *consultancy* projects. The form does not define either | `Summaries.fundedProjects` |
| (new) | What must be filled in before submitting? | A working minimum, not a college rule: Part A contact number, qualification and specialization and joining date; at least eight courses handled (4 in each of the year's 2 semesters; 2.5 marks each towards the workload component); a self-score for every criterion whose maximum is above 0 for the cadre. Everything else is optional | `AppraisalService.submitBlockers` |
| (new) | Does the declaration need a signature? | A tick and a server-recorded date (no place is asked for). The paper form has a signature line; digital signatures are open question E31 | `AppraisalService.submit` |
| (new) | Which year does a new appraisal belong to? | The most recent academic year marked `active` | `AppraisalService.create` |
| D | Supporting documents: which types, how large, how many? | **Removed.** The principal's office does not want evidence files in the system, so there is no Supporting Documents page, upload or checklist (migration `V15`). The declaration still says supporting documents are available for verification: they are kept on paper | `V15__remove_supporting_documents.sql` |
| E | Must the PDF match the official form? When is it generated? | It follows the six pages (letterhead, Part A, Part B, score sheet, declaration, HoD and Principal boxes, Annexure A). A DRAFT-watermarked copy is available at any time; the official copy is generated once at the first request after final approval and then kept unchanged. Signatures are printed lines, not digital signatures | `FormPdfBuilder`, `ReportService` |
| A | Who creates accounts? E-mail or SSO? | Administrators create every account (one at a time or from a CSV file) with the old password, which the person must change at first sign-in; the e-mail address is the sign-in name. No self-registration, no e-mail delivery, no "forgot password" link (an administrator resets it). SSO is not built | `AdminUserService`, `AccountService` |
| A | Password rules | At least 10 characters, a letter and a number, at most 72 bytes, no leading or trailing space, not a very common password, not containing the e-mail name. A working assumption, not a college rule | `PasswordPolicy` |
| (new) | Must a cadre's scoring policy total 100? | Yes, as on the form; the policy editor refuses anything else. Each criterion is a whole number 0 to 100 | `AdminSetupService.publishPolicy` |
| (new) | Where will the system run? | Only inside the college network. No runtime internet; builds are copied in; TLS and subnet restrictions at the reverse proxy are recommended | `docs/deployment.md` |
| (new) | What does the Head of the Department's console count? | The faculty of the departments they head (active accounts, plus anyone with a submitted appraisal), by stage: not yet submitted (not started or draft), with the HoD, with the Principal, approved. A draft is shown as not submitted and cannot be opened | `ConsoleService.hod` |
| (new) | What may the Principal see before the HoD has approved an appraisal? | Only a count ("not yet with you") per department and in total, never a name. The console otherwise shows what is awaiting the Principal and what is final | `ConsoleService.principal` |
| (new) | What may an administrator see of appraisals? | Counts by stage, set-up checks and recent audit entries (with file names and checksums of appraisal entries left out). Not who, and not content | `ConsoleService.admin`, `AuditQueryService` |
| (new) | What does a new academic year start with? | The latest policy of each cadre from the most recent earlier year is copied in as version 1 so appraisals can start at once; the administrator may then publish changes | `AdminSetupService.createYear` |

## Behaviour to be aware of

- A section save is rejected as a whole if any record is invalid, so a half-filled row is not saved. The frontend keeps
  an incomplete row in the browser (add-record dialog) and sends it once it is valid.
- **Known limitation, two browser tabs:** saves to one appraisal are serialised, and a section save replaces the whole
  list. If tab A adds a row and tab B, which never loaded it, saves afterwards, A's row is removed. Tabs never
  corrupt each other's data or another appraisal, but the later save wins. If this matters, add a per-section revision
  number (reject a save made from a stale read with 409). Not built yet.
- Free text is stored exactly as typed (no HTML stripping). Safety comes from escaping on output; the future PDF template
  and frontend must escape.

## Open questions (unchanged from the schematic, still unanswered)

- **A. Identity:** college e-mail accounts? Existing SSO? Administrators can now create accounts (see the assumptions
  above); whether the college wants SSO, e-mailed passwords or a self-service reset is still open.
- **B. Lifecycle:** B7 (new appraisal after final approval).
- **C. Scoring:** C15-C17, C19-C21: the exact activity-to-score formulas, sub-criteria, per-activity limits, whether
  the HoD may change a self-score, whether a separate panel score is needed. **No formula has been invented.**
- **D. Evidence:** closed: the system holds no evidence files (see the decision on D above).
- **E. Reporting:** (the form shows signature-with-seal lines for the HoD and Principal) must the PDF match the official six pages; when is it generated; signatures; whether remarks print.
- **F. Scope:** version 1 for CSE only, or all departments; department-specific fields.

## Source boundary

The supplied form defines the field structure and the cadre-wise maximum marks. It does not define how activities turn
into marks. That gap is represented as configuration (`calculation_mode`), never as hard-coded rules.
