import type { Role, Status } from "./types";

/**
 * The approval chain in one place: faculty -> Head of the Department -> Principal or Director Technical. The last two stand
 * at the same level: once the HoD has forwarded an appraisal, either may begin the review and give the final approval.
 * Every screen that depends on who comes next, what a level is waiting for, or what its box on the printed form is called
 * reads from here, so the chain is described once (and enforced by the backend's `AppraisalStatus`; this only describes
 * it). Nothing moves backwards: a submitted appraisal is never returned to its author.
 */
export type ReviewerRole = "HOD" | "PRINCIPAL" | "DIRECTOR";

export interface Level {
  role: ReviewerRole;
  /** The person, as in "the Principal". */
  title: string;
  /** Route of this level's console. */
  base: string;
  /** The status in which the appraisal is waiting for this level to begin. */
  waiting: Status;
  /** The status while this level is reviewing it. */
  reviewing: Status;
  /** The review-queue statuses that need this level's action. */
  actionable: Status[];
  /** Who receives it when this level approves; null for the last level (approval is final). */
  next: string | null;
  /** The comment box's name, as printed on the report. */
  boxTitle: string;
}

export const LEVELS: Record<ReviewerRole, Level> = {
  HOD: {
    role: "HOD", title: "Head of the Department", base: "/hod", waiting: "SUBMITTED", reviewing: "HOD_REVIEW",
    actionable: ["SUBMITTED", "HOD_REVIEW"], next: "Principal or Director Technical", boxTitle: "Recommendations of HoD",
  },
  PRINCIPAL: {
    role: "PRINCIPAL", title: "Principal", base: "/principal", waiting: "HOD_APPROVED", reviewing: "PRINCIPAL_REVIEW",
    actionable: ["HOD_APPROVED", "PRINCIPAL_REVIEW"], next: null, boxTitle: "Remarks of the Principal",
  },
  DIRECTOR: {
    role: "DIRECTOR", title: "Director Technical", base: "/director", waiting: "HOD_APPROVED", reviewing: "PRINCIPAL_REVIEW",
    actionable: ["HOD_APPROVED", "PRINCIPAL_REVIEW"], next: null, boxTitle: "Remarks of the Director Technical",
  },
};

/** The stages an appraisal passes through, in order. The Principal and the Director Technical share the last one. */
export const CHAIN: ReviewerRole[] = ["HOD", "PRINCIPAL"];

export function isReviewer(role: Role | undefined | null): role is ReviewerRole {
  return role === "HOD" || role === "PRINCIPAL" || role === "DIRECTOR";
}

/** Home screen of each role. */
export function homeFor(role: Role): string {
  if (role === "FACULTY") return "/faculty";
  if (role === "ADMIN") return "/admin";
  return LEVELS[role].base;
}

/** The road an appraisal travels, for the faculty member's "where is it" strip. */
export const JOURNEY = ["Draft", "Submitted", "With the HoD", "With the Principal / Director", "Approved"] as const;

/** Which step of {@link JOURNEY} an appraisal at this status is on. */
export const JOURNEY_STEP: Record<Status, number> = {
  DRAFT: 0,
  SUBMITTED: 1,
  HOD_REVIEW: 2,
  HOD_APPROVED: 3,
  PRINCIPAL_REVIEW: 3,
  APPROVED: 4,
};
