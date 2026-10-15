# Appraisal workflow

States and transitions are defined in `AppraisalStatus` and enforced on the backend. The UI never decides.

The approval chain is **faculty -> Head of the Department -> Principal or Director Technical**. A faculty member submits to
the HoD of their department. The HoD begins the review, may write a comment, and approves, which forwards the appraisal to
the Principal and the Director Technical. They stand at the same level (see below): either one's approval is final.
**A submitted appraisal is locked, and moves backwards in two ways only.** While the HoD is reviewing and has sent the faculty
member a message (a query), the faculty member may correct the appraisal and send it again (`RESUBMIT`), which puts it back at
SUBMITTED for the HoD to begin the review afresh. And any reviewer can **revoke** it, one level back and with remarks, at any stage
before the final approval (see "Revoke" below): the HoD's revoke goes to the faculty member, the Principal's or Director
Technical's to the HoD. Once it is finally approved, it is locked for good.

```mermaid
stateDiagram-v2
    [*] --> DRAFT
    DRAFT --> SUBMITTED: faculty submits
    SUBMITTED --> HOD_REVIEW: HoD starts review
    HOD_REVIEW --> HOD_APPROVED: HoD approves and forwards (comment optional)
    HOD_REVIEW --> SUBMITTED: faculty corrects it and sends it again (only after an HoD message)
    HOD_APPROVED --> PRINCIPAL_REVIEW: Principal or Director Technical starts review
    PRINCIPAL_REVIEW --> APPROVED: Principal or Director Technical approves (final)
    APPROVED --> [*]
```

## Who can do what

| Action | Role | From state | Notes |
|---|---|---|---|
| Create | Faculty | none | One per academic year |
| Submit | Owner | DRAFT | Needs the accepted declaration (the form's Declaration; the date is recorded by the server) and the essentials: Part A contact number, qualification and specialization and joining date. Nothing else is required: marks come from the entries and no score is typed. The API refuses with 400 and says what is missing, and the appraisal view lists it as `submitBlockers`. Everything else may be empty |
| Edit and send again | Owner | HOD_REVIEW with an open query | An open query is an HoD message the faculty member has not yet answered by sending the appraisal again (`appraisal_messages.answered_at IS NULL`). While one is open every section is editable and the declaration is made again; sending it records `RESUBMIT` (HOD_REVIEW -> SUBMITTED), stamps the open messages as answered and locks the appraisal until the HoD begins the review again. A new message opens it again. The same checks as the first submission apply |
| Start / Approve | Assigned HoD | SUBMITTED / HOD_REVIEW | The comment (max 2000 chars) is optional and is printed as "Recommendations of HoD"; approving forwards to the Principal or the Director Technical |
| Start / Approve | Principal | HOD_APPROVED / PRINCIPAL_REVIEW | Whole college; comment optional ("Remarks of the Principal"); approve is final |
| Start / Approve | Director Technical | HOD_APPROVED / PRINCIPAL_REVIEW | The same as the Principal: whole college; comment optional ("Remarks of the Director Technical"); approve is final |

| Message | Assigned HoD | HOD_REVIEW | A note to the faculty member (max 2000 chars), for example to ask them to come and discuss a query before it can be approved. It does not change the workflow status: it stays `HOD_REVIEW` and the appraisal stays locked. What changes is how it is shown: from the first message until the HoD approves, the appraisal is shown as **Query raised** (`queryRaised` on the appraisal, its list entries and the HoD console's roster) instead of "With HoD", the HoD's lists move it from "Needs your action" to "Awaiting the faculty member", and the faculty member is told to arrange a meeting. The message also counts as activity on the appraisal (its "last updated" time moves). The HoD who wrote it may edit it (`PUT .../messages/{messageId}`) while the status is `HOD_REVIEW`; the faculty member then sees it marked edited and unread again. Each replaced wording is kept (append-only) and is shown to the department's HoDs, not to the faculty member. It cannot be deleted. The faculty member sees it on their dashboard and on the Submit page; it is marked seen when they open it, and the HoD sees that. Only the owner and the assigned HoD can read it |

There is no other step that moves an appraisal. `POST /api/appraisals/{id}/review/return` does not exist (404), and neither
do the Dean and Vice Principal areas. A message is not a return. Any of several HoDs assigned to a department may act; if two start at once exactly one wins and the
other gets 409.

The comments reviewers write with an approval are **not shown to the faculty member**: the API removes them from the review
history the faculty member receives, and the PDF the faculty member downloads leaves them out (the official copy stored at
approval has them). A message from the HoD is the one thing in words the faculty member is shown during the review.

## Revoke (HoD, Principal, Director Technical)

A reviewer can send an appraisal **one level back** at any stage **before the final approval**, **without anyone's consent**
(`POST /api/appraisals/{id}/review/revoke`, body `{"reason": "..."}`; the remarks are required, at most 2000 characters). Where it
goes depends on who revokes:

| Who | From | To | Why there |
|---|---|---|---|
| Assigned HoD | SUBMITTED, HOD_REVIEW, HOD_APPROVED, PRINCIPAL_REVIEW | DRAFT | it goes back to the faculty member, with the HoD's remarks, to correct and send again (including one already forwarded) |
| Principal | HOD_APPROVED, PRINCIPAL_REVIEW | HOD_REVIEW | it goes back to the HoD, with the Principal's remarks, to review again; the HoD can then forward it again or revoke it to the faculty member |
| Director Technical | HOD_APPROVED, PRINCIPAL_REVIEW | HOD_REVIEW | the same, with the Director Technical's remarks |
| anyone | APPROVED | not possible | final approval is final (409) |

The Principal and the Director Technical see only what the HoD has forwarded, so they cannot revoke anything earlier (404). Every
revoke is a review-history row (`HOD_REVOKE`, `PRINCIPAL_REVOKE` or `DIRECTOR_REVOKE`, with the remarks as its comment) and an audit
entry `APPRAISAL_REVOKED` carrying who revoked (`revokedBy`, `revokerId`), the time, the remarks and the previous and new statuses.
Open questions to the faculty member are closed with it.

**Who is shown which remarks.** The remarks are written for the level the appraisal goes back to, and for no one else:

| Reader | Sees |
|---|---|
| Faculty member | the HoD's revoke remarks (`returnedNotice` while it is a draft again after a revoke, and `revocations` for every earlier one). Never the review history, an approval comment, or the Principal's or Director Technical's remarks. After a Principal or Director revoke it only reads "With HoD" |
| HoD | the whole review history, with the Principal's and Director Technical's **approval** comments removed (their feedback is not the HoD's to read); the remarks of a Principal or Director **revoke** (`returnedNotice`); and the revoke and resubmit trail below |
| Principal, Director Technical | only the HoD's forwarding (with its recommendation) and their own level's steps. The HoD's revoke steps and remarks are not sent to them |

The printed form follows the same rule: the faculty member's copy leaves out every reviewer's remarks, the HoD's leaves out
the Principal's and the Director Technical's, and the official copy stored at approval keeps all of them.

**Revoke and resubmit trail (HoD).** `GET /api/appraisals/{id}/revocations` gives the HoD of the faculty member's department the
number of times the appraisal has been revoked (by anyone) and sent again by the faculty member, and every submission,
resubmission and revocation with who, when and what they wrote. It works while the appraisal is a draft again, which the HoD
cannot open (it carries no appraisal content). The HoD console shows the counts on each faculty member's row, with a way to open
the trail, and marks a revoked appraisal that is with the faculty member as "Revoked, with the faculty member".

## Visibility

| Viewer | Can open an appraisal when |
|---|---|
| Faculty | it is their own |
| HoD | it is in their assigned department and **not a draft** |
| Principal, Director Technical | the HoD has approved it (HOD_APPROVED, PRINCIPAL_REVIEW or APPROVED) |
| Admin | never (configuration role only) |

Anything not visible returns **404**, so existence is not leaked. The consoles apply the same rules to what they count
(`docs/requirements.md`).

## The Director Technical

The Director Technical (`DIRECTOR`, migration V23) stands at the Principal's level. No status was added; the two share
`HOD_APPROVED` and `PRINCIPAL_REVIEW`:

- Either one may begin the review of an appraisal the HoD has forwarded, and either may give the approval, whichever of
  them began it. Once one has begun, the other cannot begin again (409); the status is what decides, not who acted first.
- Each has their own steps (`START_DIRECTOR_REVIEW`, `DIRECTOR_APPROVE` beside `START_PRINCIPAL_REVIEW`,
  `PRINCIPAL_APPROVE`), so the review history, the audit trail and the report say which of them acted.
- Their console (`/director`, `GET /api/director/console`) is the Principal's console, with the same college-wide figures.
  Each console is open to its own role only: the Principal gets 403 on the Director's and the Director on the Principal's.
- The printed report has a box for each: **Remarks of the Principal** and **Remarks of the Director Technical**. Both are
  always printed. The one that did not act on that appraisal is left empty, to be signed on paper if the college wishes.
  Official reports issued before V23 are stored as they were and do not gain the box.
- The administrator's set-up check asks for one active Principal **or** Director Technical account.

## Integrity

- A transition is `UPDATE ... WHERE id = ? AND status = ?`. If two requests race, exactly one wins and the other gets
  409. This is covered by concurrency tests (8 simultaneous submits; two HoDs starting at once).
- Every transition writes a `review_actions` row and an `audit_logs` row in the same transaction.
- `review_actions` is append-only (database triggers reject UPDATE and DELETE). `audit_logs` cannot be edited, but an administrator may delete entries; each deletion is recorded (see `docs/security.md`).
- The database also refuses `APPROVED` without an approval timestamp, and the reverse; `appraisals.status` and
  `users.role` are CHECK-constrained to the values of this chain.

## The earlier, longer chain

Until migration V8 the chain was faculty -> HoD -> Dean -> Vice Principal -> Principal, and every reviewer could return
an appraisal to the faculty member. That was withdrawn. What happened under it is kept, not rewritten:

- `review_actions` and `audit_logs` still name the old steps, statuses and roles exactly as they happened, and the
  screens and the report can still read them (an older appraisal's history shows "Dean recommended it", and its report
  keeps the Dean's and Vice Principal's remarks).
- An appraisal that stood at a withdrawn status was moved once, with an audit entry (`APPRAISAL_STATUS_MIGRATED`):
  returned to its author -> `DRAFT` (it was open for correction; it is submitted again); with, or passed by, the Dean
  or the Vice Principal -> `HOD_APPROVED` (it now waits for the Principal).
- Dean and Vice Principal accounts were closed (`USER_ROLE_WITHDRAWN` in the audit trail). They keep their role for
  the record, cannot sign in, and cannot be re-opened: the database allows those two roles only on a disabled account,
  and the administrator's screens refuse to change one. `dean_assignments` is left in place, unused.
