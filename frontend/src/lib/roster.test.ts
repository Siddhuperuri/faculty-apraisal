import { describe, expect, it } from "vitest";
import { filterRoster, viewOf } from "./roster";
import type { RosterRow } from "./types";

const row = (name: string, o: Partial<RosterRow> = {}): RosterRow => ({
  appraisalId: 1, name, employeeId: `E-${name}`, department: "CSE", cadre: "Professor", status: null,
  stage: "NOT_SUBMITTED", submittedAt: null, updatedAt: null, queryRaised: false, ...o,
});

const rows = [
  row("Asha", { stage: "NEEDS_HOD", status: "SUBMITTED", submittedAt: "2026-10-05T10:00:00Z" }),
  row("Bala", { stage: "NEEDS_HOD", status: "HOD_REVIEW", queryRaised: true, submittedAt: "2026-10-01T10:00:00Z" }),
  row("Chitra", { stage: "APPROVED", cadre: "Lecturer", department: "ECE", submittedAt: "2026-10-03T10:00:00Z" }),
  row("Deepak"),
];
const none = { view: "" as const, department: "", cadre: "", query: "" };

describe("viewOf", () => {
  it("separates a raised query from what needs the HoD", () => {
    expect(viewOf(rows[0])).toBe("NEEDS_ACTION");
    expect(viewOf(rows[1])).toBe("QUERY");
    expect(viewOf(rows[2])).toBe("APPROVED");
    expect(viewOf(rows[3])).toBe("NOT_SUBMITTED");
  });
});

describe("filterRoster", () => {
  it("keeps everyone when nothing is chosen", () => {
    expect(filterRoster(rows, none, "")).toHaveLength(4);
  });

  it("filters by view, department, cadre and search together", () => {
    expect(filterRoster(rows, { ...none, view: "QUERY" }, "").map((r) => r.name)).toEqual(["Bala"]);
    expect(filterRoster(rows, { ...none, department: "ECE" }, "").map((r) => r.name)).toEqual(["Chitra"]);
    expect(filterRoster(rows, { ...none, cadre: "Professor" }, "")).toHaveLength(3);
    expect(filterRoster(rows, { ...none, cadre: "Professor", query: " deep " }, "").map((r) => r.name)).toEqual(["Deepak"]);
  });

  it("sorts by name, and by submission date with the unsubmitted last", () => {
    expect(filterRoster(rows, none, "NAME").map((r) => r.name)).toEqual(["Asha", "Bala", "Chitra", "Deepak"]);
    expect(filterRoster(rows, none, "OLDEST").map((r) => r.name)).toEqual(["Bala", "Chitra", "Asha", "Deepak"]);
    expect(filterRoster(rows, none, "NEWEST").map((r) => r.name)).toEqual(["Asha", "Chitra", "Bala", "Deepak"]);
  });
});
