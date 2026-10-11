import type { Role, Stage, Status, WithdrawnRole } from "./types";

/**
 * Wording of the dropdown values comes from the database-managed lists (served with each field by /api/sections/meta and
 * registered here when the definitions load); only the two structural codes below are known to the screens themselves.
 */
const FIXED: Record<string, string> = {
  INSTITUTE: "Institute level",
  DEPARTMENT: "Department level",
};

/** code -> label across every list, and label by section.field for lists whose codes mean different things in different fields. */
let byCode: Record<string, string> = {};
let byField: Record<string, Record<string, string>> = {};

/** Registers the labels the server sent with the section definitions (called when they are loaded). */
export function registerLabels(sections: { key: string; fields: { name: string; labels?: Record<string, string> | null }[] }[]) {
  const codes: Record<string, string> = {};
  const fields: Record<string, Record<string, string>> = {};
  for (const sec of sections) {
    for (const f of sec.fields) {
      if (!f.labels) continue;
      fields[`${sec.key}.${f.name}`] = f.labels;
      // numbers (semester, hours, author position) mean different things in different fields: only a field can label them
      if (Object.keys(f.labels).every((c) => /^\d+$/.test(c))) continue;
      for (const [code, label] of Object.entries(f.labels)) if (!(code in codes)) codes[code] = label;
    }
  }
  byCode = codes;
  byField = fields;
}

export function enumLabel(value: string): string {
  if (byCode[value]) return byCode[value];
  if (FIXED[value]) return FIXED[value];
  return value.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
}

/** The label of a value of one field of one section ("1" is "1st author" for the author position, "1" for a semester). */
export function fieldLabel(section: string, field: string, value: unknown): string {
  if (value == null || value === "") return "";
  const v = String(value);
  return byField[`${section}.${field}`]?.[v] ?? enumLabel(v);
}

export const STATUS_INFO: Record<Status, { label: string; tone: "neutral" | "info" | "warn" | "ok"; faculty: string }> = {
  DRAFT: { label: "Draft", tone: "neutral", faculty: "Complete your sections, then submit to your HoD. It cannot be changed after that, unless your HoD sends you a message about it." },
  SUBMITTED: { label: "Submitted", tone: "info", faculty: "Submitted. Waiting for your HoD to begin the review." },
  HOD_REVIEW: { label: "With HoD", tone: "info", faculty: "Your HoD is reviewing your appraisal." },
  HOD_APPROVED: { label: "HoD recommended", tone: "info", faculty: "Approved by your HoD and forwarded to the Principal or Director Technical." },
  PRINCIPAL_REVIEW: { label: "With Principal / Director", tone: "info", faculty: "The Principal or Director Technical is reviewing your appraisal." },
  APPROVED: { label: "Approved", tone: "ok", faculty: "Your appraisal has been approved." },
};

/**
 * Where an appraisal stands in one line. Once the HoD has messaged the faculty member during the review the appraisal is
 * shown as having a query raised: it is still with the HoD (the workflow status does not change), but it is waiting for
 * the faculty member to come and talk, not for the HoD.
 */
export function statusLine(status: Status, queryRaised: boolean, forFaculty: boolean): string {
  if (status === "HOD_REVIEW" && queryRaised) {
    return forFaculty
      ? "Your HoD has sent you a message about your appraisal. Please arrange to meet them. Until they approve it you may correct your appraisal and send it again."
      : "A query has been raised with the faculty member. It is waiting for them to meet you.";
  }
  return forFaculty ? STATUS_INFO[status].faculty : STATUS_INFO[status].label;
}

export const ROLE_LABEL: Record<Role, string> = {
  FACULTY: "Faculty",
  HOD: "Head of the Department",
  PRINCIPAL: "Principal",
  DIRECTOR: "Director Technical",
  ADMIN: "Administrator",
};

/** Roles that were withdrawn. They still name who acted in older review history, and closed accounts in the list. */
const WITHDRAWN_ROLE_LABEL: Record<WithdrawnRole, string> = { DEAN: "Dean", VICE_PRINCIPAL: "Vice Principal" };

export function isWithdrawnRole(role: Role | WithdrawnRole): role is WithdrawnRole {
  return role in WITHDRAWN_ROLE_LABEL;
}

export function roleLabel(role: Role | WithdrawnRole): string {
  return isWithdrawnRole(role) ? WITHDRAWN_ROLE_LABEL[role] : ROLE_LABEL[role];
}

/**
 * Plain-language names for history entries. The second group no longer happens (there is no Dean or Vice Principal
 * step and nothing is returned); the names remain so the history of an older appraisal still reads as it happened.
 */
export const ACTION_LABEL: Record<string, string> = {
  SUBMIT: "Submitted by faculty",
  RESUBMIT: "Corrected and sent again by faculty",
  START_HOD_REVIEW: "HoD began the review",
  HOD_APPROVE: "HoD approved it and forwarded it to the Principal or Director Technical",
  START_PRINCIPAL_REVIEW: "Principal began the review",
  PRINCIPAL_APPROVE: "Principal approved it",
  START_DIRECTOR_REVIEW: "Director Technical began the review",
  DIRECTOR_APPROVE: "Director Technical approved it",

  HOD_RETURN: "HoD returned it",
  START_DEAN_REVIEW: "Dean began the review",
  DEAN_RETURN: "Dean returned it",
  DEAN_APPROVE: "Dean recommended it",
  START_VP_REVIEW: "Vice Principal began the review",
  VP_RETURN: "Vice Principal returned it",
  VP_APPROVE: "Vice Principal recommended it",
  PRINCIPAL_RETURN: "Principal returned it",
};

/** How the consoles group appraisals, in plain words. */
export const STAGE_LABEL: Record<Stage, string> = {
  NOT_SUBMITTED: "Not yet submitted",
  NEEDS_HOD: "With the HoD",
  ONWARD: "With the Principal / Director",
  APPROVED: "Approved",
};
