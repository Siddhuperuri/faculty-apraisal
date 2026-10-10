import type { Role, Stage, Status, WithdrawnRole } from "./types";

/** Readable text for stored enum values, using the wording printed on the official form. */
const LABELS: Record<string, string> = {
  INST: "Institute", STATE: "State", NAT: "National", INTL: "International",
  THEORY: "Theory", LAB: "Lab",
  B_TECH: "B.Tech", PHARMACY: "Pharmacy",
  CSE: "CSE", AIML: "AI & ML", ECE: "ECE", EEE: "EEE", ME: "ME", CE: "CE", BSH: "BSH",
  PHARMACEUTICS: "Pharmaceutics", PHARMACEUTICAL_CHEMISTRY: "Pharmaceutical Chemistry", PHARMACOLOGY: "Pharmacology",
  PHARMACOGNOSY: "Pharmacognosy", PHARMACY_PRACTICE: "Pharmacy Practice",
  "1": "1st author", "2": "2nd author", "3": "3rd author", "4": "4th author", "5": "5th author", "6": "6th author", "7": "7th author", "8": "8th author",
  DIPLOMA: "Diploma", UG: "UG", PG: "PG",
  PAPER: "Paper", PATENT: "Patent", PROTOTYPE: "Prototype", COMPETITION: "Competition", NONE: "None",
  OFFLINE: "Offline", ONLINE: "Online", BLENDED: "Blended",
  NPTEL: "NPTEL", SWAYAM: "Swayam", COURSERA: "Coursera", OTHER: "Other",
  INSTITUTE: "Institute level", DEPARTMENT: "Department level",
  SCI_SCIE: "SCI / SCIE", SCOPUS: "Scopus", UGC_CARE_ABDC: "UGC-CARE / ABDC", OTHERS: "Others",
  GOOGLE_SCHOLAR: "Google Scholar", WEB_OF_SCIENCE: "Web of Science",
  PHD: "Ph.D.", MTECH: "M.Tech", MBA: "MBA",
  REGISTERED: "Registered", SUBMITTED: "Submitted", AWARDED: "Awarded",
  PURSUING: "Pursuing", NOT_APPLICABLE: "Not applicable",
  COURSE_WORK: "Course work", COMPREHENSIVE_PROPOSAL: "Comprehensive / Proposal",
  SYNOPSIS: "Synopsis / Pre-Ph.D.", THESIS_SUBMITTED: "Thesis submitted", VIVA_COMPLETED: "Viva-voce completed",
  PI: "PI", CO_PI: "Co-PI", RESEARCH: "Research", CONSULTANCY: "Consultancy",
  SANCTIONED: "Sanctioned", APPLIED: "Applied",
  DESIGN: "Design", UTILITY: "Utility", COPYRIGHT: "Copyright",
  FILED: "Filed", PUBLISHED: "Published", GRANTED: "Granted",
  BOOK: "Book", CHAPTER: "Chapter",
  CONFERENCE_SESSION_CHAIR: "Conference Session Chair",
  EXPERT_LECTURE_DELIVERED: "Expert Lectures Delivered",
  RESOURCE_PERSON: "Resource Person",
  EDITORIAL_BOARD_MEMBER: "Editorial Board Member",
  JOURNAL_REVIEWER: "Reviewer for Journals",
  EXTERNAL_EXAMINER: "External Examiner",
  EXTERNAL_THESIS_EVALUATED: "External Thesis Evaluated",
  VISITING_RESEARCHER: "Visiting Researcher",
  INDUSTRY_INTERACTION_MOU: "Industry Interaction or MoU",
  INTERNATIONAL_CONFERENCE_ATTENDED: "International Conferences Attended",
};

export function enumLabel(value: string): string {
  if (LABELS[value]) return LABELS[value];
  return value.toLowerCase().replace(/_/g, " ").replace(/^\w/, (c) => c.toUpperCase());
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
