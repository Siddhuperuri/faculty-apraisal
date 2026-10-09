/** Plain-language names for audit-trail actions. Unknown actions fall back to a readable form of the code. */
export const AUDIT_ACTION_LABEL: Record<string, string> = {
  USER_CREATED: "Account created",
  USERS_IMPORTED: "Accounts created from a file",
  RESTORE_TEST_RECORDED: "Restore test recorded",
  HANDOVER_UPDATED: "Handover notes updated",
  USER_UPDATED: "Account details changed",
  USER_DISABLED: "Account disabled",
  USER_ENABLED: "Account enabled",
  PASSWORD_RESET: "Password reset by an administrator",
  PASSWORD_CHANGED: "Password changed by the user",
  ADMIN_BOOTSTRAPPED: "First administrator created",
  DEPARTMENT_CREATED: "Department added",
  DEPARTMENT_UPDATED: "Department changed",
  ACADEMIC_YEAR_CREATED: "Academic year opened",
  ACADEMIC_YEAR_OPENED: "Academic year reopened",
  ACADEMIC_YEAR_CLOSED: "Academic year closed",
  SCORING_POLICY_PUBLISHED: "Scoring policy published",
  APPRAISAL_CREATED: "Appraisal started",
  APPRAISAL_SUBMIT: "Appraisal submitted",
  APPRAISAL_START_HOD_REVIEW: "HoD began the review",
  APPRAISAL_MESSAGE_SENT: "HoD sent a message to the faculty member",
  APPRAISAL_MESSAGE_EDITED: "HoD edited a message to the faculty member",
  APPRAISAL_HOD_APPROVE: "HoD approved the appraisal and forwarded it",
  APPRAISAL_START_PRINCIPAL_REVIEW: "Principal began the review",
  APPRAISAL_PRINCIPAL_APPROVE: "Principal approved the appraisal",
  APPRAISAL_START_DIRECTOR_REVIEW: "Director Technical began the review",
  APPRAISAL_DIRECTOR_APPROVE: "Director Technical approved the appraisal",
  // Entries written before the Dean and Vice Principal levels and the return step were withdrawn, and by that change.
  APPRAISAL_HOD_RETURN: "HoD returned the appraisal",
  APPRAISAL_START_DEAN_REVIEW: "Dean began the review",
  APPRAISAL_DEAN_RETURN: "Dean returned the appraisal",
  APPRAISAL_DEAN_APPROVE: "Dean recommended the appraisal",
  APPRAISAL_START_VP_REVIEW: "Vice Principal began the review",
  APPRAISAL_VP_RETURN: "Vice Principal returned the appraisal",
  APPRAISAL_VP_APPROVE: "Vice Principal recommended the appraisal",
  APPRAISAL_PRINCIPAL_RETURN: "Principal returned the appraisal",
  APPRAISAL_STATUS_MIGRATED: "Appraisal moved to the two-level chain",
  APPRAISAL_POLICY_MOVED: "Draft moved to the current scoring policy",
  USER_ROLE_WITHDRAWN: "Account closed: its role was withdrawn",
  SECTION_SAVED: "Form section saved",
  SCORES_SAVED: "Self-scores saved",
  DOCUMENT_UPLOADED: "Supporting document uploaded",
  DOCUMENT_DELETED: "Supporting document removed",
  REPORT_ISSUED: "Official report issued",
  AUDIT_DELETED: "Audit entries deleted",
};

export function auditActionLabel(code: string): string {
  return AUDIT_ACTION_LABEL[code] ?? code.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

/** The actions an administrator is most likely to look for, for the filter. */
export const AUDIT_FILTER_ACTIONS = [
  "USER_CREATED",
  "USERS_IMPORTED",
  "USER_UPDATED",
  "USER_DISABLED",
  "USER_ENABLED",
  "PASSWORD_RESET",
  "PASSWORD_CHANGED",
  "DEPARTMENT_CREATED",
  "DEPARTMENT_UPDATED",
  "ACADEMIC_YEAR_CREATED",
  "ACADEMIC_YEAR_OPENED",
  "ACADEMIC_YEAR_CLOSED",
  "SCORING_POLICY_PUBLISHED",
  "APPRAISAL_CREATED",
  "APPRAISAL_SUBMIT",
  "APPRAISAL_HOD_APPROVE",
  "APPRAISAL_PRINCIPAL_APPROVE",
  "APPRAISAL_DIRECTOR_APPROVE",
  "REPORT_ISSUED",
];

/** A short "key: value" line for the details of an entry (already stripped of appraisal content by the server). */
export function describeDetails(details: Record<string, unknown> | null): string {
  if (!details) return "";
  const names: Record<string, string> = {
    email: "E-mail", role: "Role", accounts: "Accounts", FACULTY: "Faculty", HOD: "HoD", PRINCIPAL: "Principal", DIRECTOR: "Director Technical", ADMIN: "Administrator", removed: "Entries removed", code: "Code", name: "Name", active: "Active", version: "Version", policiesCopied: "Policies copied",
    section: "Section", changes: "Changes", documentId: "Document", category: "Category", size: "Size (bytes)", bytes: "Size (bytes)",
    academicYearId: "Year id", cadreId: "Cadre id", from: "From", to: "To",
  };
  return Object.entries(details)
    .map(([k, v]) => `${names[k] ?? k}: ${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
    .join(" · ");
}
