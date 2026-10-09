import { describe, expect, it } from "vitest";
import { CHAIN, JOURNEY, JOURNEY_STEP, LEVELS, homeFor, isReviewer, type ReviewerRole } from "./hierarchy";
import { ACTION_LABEL, ROLE_LABEL, STAGE_LABEL, STATUS_INFO, roleLabel, statusLine } from "./labels";
import type { Role, Stage, Status } from "./types";

const ALL_STATUSES = Object.keys(STATUS_INFO) as Status[];
const ALL_ROLES = Object.keys(ROLE_LABEL) as Role[];

describe("the approval chain", () => {
  it("runs HoD then Principal, and the Principal waits for the HoD's approval", () => {
    expect(CHAIN).toEqual(["HOD", "PRINCIPAL"]);
    expect(LEVELS.HOD.waiting).toBe("SUBMITTED");
    expect(LEVELS.HOD.next).toContain(LEVELS.PRINCIPAL.title);
    expect(LEVELS.PRINCIPAL.waiting).toBe("HOD_APPROVED");
    expect(LEVELS.PRINCIPAL.next).toBeNull();
  });

  it("puts the Director Technical at the Principal's level: the same statuses, the same finality, a box of their own", () => {
    const [principal, director] = [LEVELS.PRINCIPAL, LEVELS.DIRECTOR];
    expect(director.waiting).toBe(principal.waiting);
    expect(director.reviewing).toBe(principal.reviewing);
    expect(director.actionable).toEqual(principal.actionable);
    expect(director.next).toBeNull();
    expect(LEVELS.HOD.next).toContain(director.title);
    expect(director.base).not.toBe(principal.base);
    expect(director.boxTitle).not.toBe(principal.boxTitle);
  });

  it("has no Dean, no Vice Principal and no way back", () => {
    expect(ALL_ROLES.sort()).toEqual(["ADMIN", "DIRECTOR", "FACULTY", "HOD", "PRINCIPAL"]);
    expect(ALL_STATUSES).toEqual(["DRAFT", "SUBMITTED", "HOD_REVIEW", "HOD_APPROVED", "PRINCIPAL_REVIEW", "APPROVED"]);
    expect(ALL_STATUSES.some((s) => s.includes("RETURNED") || s.startsWith("DEAN") || s.startsWith("VP"))).toBe(false);
    for (const r of Object.keys(LEVELS) as ReviewerRole[]) expect(Object.keys(LEVELS[r]).some((k) => k.toLowerCase().includes("return"))).toBe(false);
  });

  it("gives each level its own routes, statuses and box on the report", () => {
    const reviewers = Object.keys(LEVELS) as ReviewerRole[];
    expect(new Set(reviewers.map((r) => LEVELS[r].base)).size).toBe(3);
    expect(new Set(reviewers.map((r) => LEVELS[r].boxTitle)).size).toBe(3);
    for (const r of reviewers) {
      const l = LEVELS[r];
      expect(l.actionable).toEqual([l.waiting, l.reviewing]);
      expect(ALL_STATUSES).toContain(l.waiting);
      expect(ALL_STATUSES).toContain(l.reviewing);
      expect(l.boxTitle.length).toBeGreaterThan(5);
      expect(isReviewer(r)).toBe(true);
    }
    expect(isReviewer("FACULTY")).toBe(false);
    expect(isReviewer("ADMIN")).toBe(false);
    expect(isReviewer(undefined)).toBe(false);
  });

  it("sends each role to its own home", () => {
    expect(homeFor("FACULTY")).toBe("/faculty");
    expect(homeFor("HOD")).toBe("/hod");
    expect(homeFor("PRINCIPAL")).toBe("/principal");
    expect(homeFor("DIRECTOR")).toBe("/director");
    expect(homeFor("ADMIN")).toBe("/admin");
  });
});

describe("labels cover every role, status and stage", () => {
  it("names every role and every status in plain words", () => {
    for (const r of ALL_ROLES) expect(ROLE_LABEL[r], r).toBeTruthy();
    for (const s of ALL_STATUSES) {
      expect(STATUS_INFO[s].label, s).toBeTruthy();
      expect(STATUS_INFO[s].faculty.length, s).toBeGreaterThan(10);
    }
  });

  it("names every step of the chain", () => {
    for (const a of ["SUBMIT", "START_HOD_REVIEW", "HOD_APPROVE", "START_PRINCIPAL_REVIEW", "PRINCIPAL_APPROVE", "START_DIRECTOR_REVIEW", "DIRECTOR_APPROVE"]) {
      expect(ACTION_LABEL[a], a).toBeTruthy();
    }
  });

  it("can still read the history of an older appraisal: withdrawn steps and roles keep their names", () => {
    for (const a of ["HOD_RETURN", "START_DEAN_REVIEW", "DEAN_RETURN", "DEAN_APPROVE", "START_VP_REVIEW", "VP_RETURN", "VP_APPROVE", "PRINCIPAL_RETURN"]) {
      expect(ACTION_LABEL[a], a).toBeTruthy();
    }
    expect(roleLabel("DEAN")).toBe("Dean");
    expect(roleLabel("VICE_PRINCIPAL")).toBe("Vice Principal");
    expect(roleLabel("HOD")).toBe(ROLE_LABEL.HOD);
  });

  it("names every stage of the HoD console", () => {
    const stages: Stage[] = ["NOT_SUBMITTED", "NEEDS_HOD", "ONWARD", "APPROVED"];
    for (const s of stages) expect(STAGE_LABEL[s], s).toBeTruthy();
    expect(Object.keys(STAGE_LABEL).sort()).toEqual([...stages].sort());
  });
});

describe("the faculty member's journey", () => {
  it("places every status on a step, moving only forward", () => {
    for (let i = 0; i < ALL_STATUSES.length; i++) {
      expect(JOURNEY_STEP[ALL_STATUSES[i]], ALL_STATUSES[i]).toBeLessThan(JOURNEY.length);
      if (i > 0) expect(JOURNEY_STEP[ALL_STATUSES[i]]).toBeGreaterThanOrEqual(JOURNEY_STEP[ALL_STATUSES[i - 1]]);
    }
    expect(JOURNEY_STEP.DRAFT).toBe(0);
    expect(JOURNEY_STEP.APPROVED).toBe(JOURNEY.length - 1);
  });
});

describe("the status line once the HoD has messaged the faculty member", () => {
  it("says a query is raised, for each side, only while the HoD is reviewing", () => {
    expect(statusLine("HOD_REVIEW", true, true)).toMatch(/message.*meet/i);
    expect(statusLine("HOD_REVIEW", true, false)).toMatch(/query.*raised/i);
    // no message: the plain status, as before
    expect(statusLine("HOD_REVIEW", false, true)).toBe(STATUS_INFO.HOD_REVIEW.faculty);
    expect(statusLine("HOD_REVIEW", false, false)).toBe(STATUS_INFO.HOD_REVIEW.label);
    // a query flag on any other status changes nothing
    for (const status of ALL_STATUSES.filter((x) => x !== "HOD_REVIEW")) {
      expect(statusLine(status, true, true), status).toBe(STATUS_INFO[status].faculty);
      expect(statusLine(status, true, false), status).toBe(STATUS_INFO[status].label);
    }
  });
});
