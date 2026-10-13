import { describe, expect, it } from "vitest";
import { criterionSummary, perEntryLabel } from "./score";

describe("what a faculty member sees of a criterion", () => {
  it("is its number and title, what it is worth and what they earned, nothing of the working", () => {
    expect(criterionSummary("B1", "Teaching & Learning", 0, 40)).toBe("B1 · Teaching & Learning — 0 of 40");
    expect(criterionSummary("B1", "Teaching & Learning", 27.5, 40)).toBe("B1 · Teaching & Learning — 27.5 of 40");
    expect(criterionSummary("B2", "Student Mentoring, Guidance & Achievements", 12.34567, 15)).toBe(
      "B2 · Student Mentoring, Guidance & Achievements — 12.35 of 15",
    );
  });

  it("a criterion without a maximum says so", () => {
    expect(criterionSummary("B6", "Funded Projects / Consultancy", 60, null)).toBe("B6 · Funded Projects / Consultancy — 60 (no maximum)");
  });

  it("never carries per-entry arithmetic", () => {
    const text = criterionSummary("B1", "Teaching & Learning", 12.5, 40);
    expect(text).not.toMatch(/×|\bx\b|\beach\b|\d\s*\//);
  });
});

describe("per-entry rates", () => {
  it("are worded as marks for each, never as a number and 'each'", () => {
    expect(perEntryLabel(50)).toBe("50m for each");
    expect(perEntryLabel(5)).toBe("5m for each");
    expect(perEntryLabel(2.5)).toBe("2.5m for each");
    expect(perEntryLabel(1.25)).toBe("1.25m for each");
    expect(perEntryLabel(50)).not.toMatch(/\(50 each\)|50 each/);
  });
});
