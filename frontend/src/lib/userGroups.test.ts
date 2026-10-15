import { describe, expect, it } from "vitest";
import { NO_FILTERS, filterGroups, optionsFor, percent, tally } from "./userGroups";
import type { UserGroup } from "./types";

const groups: UserGroup[] = [
  { role: "FACULTY", status: "ACTIVE", department: "CSE", designation: "Assistant Professor", count: 10 },
  { role: "FACULTY", status: "ACTIVE", department: "ECE", designation: "Professor", count: 4 },
  { role: "FACULTY", status: "DISABLED", department: "CSE", designation: "Professor", count: 2 },
  { role: "HOD", status: "ACTIVE", department: "CSE", designation: null, count: 1 },
  { role: "ADMIN", status: "ACTIVE", department: null, designation: null, count: 2 },
];

describe("the user pie chart's figures", () => {
  it("counts everyone when nothing is filtered", () => {
    expect(tally(filterGroups(groups, NO_FILTERS), "role")).toEqual([
      { key: "FACULTY", value: 16 },
      { key: "ADMIN", value: 2 },
      { key: "HOD", value: 1 },
    ]);
  });

  it("narrows by every filter together", () => {
    const f = { ...NO_FILTERS, role: "FACULTY", department: "CSE" };
    expect(tally(filterGroups(groups, f), "status")).toEqual([
      { key: "ACTIVE", value: 10 },
      { key: "DISABLED", value: 2 },
    ]);
  });

  it("keeps accounts that have no department together at the end", () => {
    expect(tally(groups, "department")).toEqual([
      { key: "CSE", value: 13 },
      { key: "ECE", value: 4 },
      { key: "", value: 2 },
    ]);
  });

  it("offers only the values that exist, in order", () => {
    expect(optionsFor(groups, "department")).toEqual(["CSE", "ECE"]);
    expect(optionsFor(groups, "designation")).toEqual(["Assistant Professor", "Professor"]);
  });

  it("works out shares to one decimal and never divides by zero", () => {
    expect(percent(1, 3)).toBe(33.3);
    expect(percent(0, 0)).toBe(0);
  });
});
