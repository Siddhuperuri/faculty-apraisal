import { describe, expect, it } from "vitest";
import type { SectionMeta } from "./types";
import { applyDependencies, choicesFor, inclusiveDays, outsideYear, validate, type FormValues } from "./validate";

const base = { required: true, maxLength: null, min: null, max: null, scale: null };

const meta = {
  key: "demo",
  singleton: false,
  uniqueField: null,
  dateRanges: [{ startField: "startDate", endField: "endDate" }],
  fields: [
    { ...base, name: "program", label: "Program", type: "ENUM", allowed: ["B_TECH", "MBA"] },
    {
      ...base, name: "branch", label: "Branch", type: "ENUM", allowed: ["CSE", "ECE", "MBA"], dependsOn: "program",
      allowedBy: { B_TECH: ["CSE", "ECE"], MBA: ["MBA"] },
    },
    { ...base, name: "startDate", label: "Start date", type: "DATE", allowed: null },
    { ...base, name: "endDate", label: "End date", type: "DATE", allowed: null },
    { ...base, name: "days", label: "Number of days", type: "INT", min: 1, max: 366, allowed: null, derived: true },
  ],
} as SectionMeta;

describe("inclusiveDays", () => {
  it("counts both end days", () => {
    expect(inclusiveDays("2026-01-30", "2026-02-02")).toBe("4");
    expect(inclusiveDays("2026-03-05", "2026-03-05")).toBe("1");
    expect(inclusiveDays("2024-02-28", "2024-03-01")).toBe("3"); // a leap year
  });

  it("is empty while a date is missing or the range is reversed", () => {
    expect(inclusiveDays("", "2026-03-05")).toBe("");
    expect(inclusiveDays("2026-03-05", "")).toBe("");
    expect(inclusiveDays("2026-03-06", "2026-03-05")).toBe("");
    expect(inclusiveDays("05/03/2026", "2026-03-06")).toBe("");
  });
});

describe("a choice that depends on another", () => {
  const values = { program: "B_TECH", branch: "CSE", startDate: "", endDate: "", days: "" };

  it("offers only what suits the value above it", () => {
    expect(choicesFor(meta.fields[1], values)).toEqual(["CSE", "ECE"]);
    expect(choicesFor(meta.fields[1], { ...values, program: "MBA" })).toEqual(["MBA"]);
    expect(choicesFor(meta.fields[1], { ...values, program: "" })).toEqual([]);
    expect(choicesFor(meta.fields[0], values)).toEqual(["B_TECH", "MBA"]);
  });

  it("clears a branch the new program does not offer, and keeps one it does", () => {
    expect(applyDependencies(meta, { ...values, program: "MBA" }, "program").branch).toBe("");
    expect(applyDependencies(meta, { ...values, program: "B_TECH", branch: "ECE" }, "program").branch).toBe("ECE");
  });

  it("rejects a branch that does not belong to the program", () => {
    expect(validate(meta, { ...values, branch: "MBA", startDate: "2026-01-01", endDate: "2026-01-02" }).branch).toBe("Branch is not offered under the choice above.");
    expect(validate(meta, { ...values, startDate: "2026-01-01", endDate: "2026-01-02" })).toEqual({});
  });
});

describe("a derived field", () => {
  it("follows the dates as they are typed and is not asked for", () => {
    let v: FormValues = { program: "B_TECH", branch: "CSE", startDate: "", endDate: "", days: "" };
    v = applyDependencies(meta, { ...v, startDate: "2026-07-01" }, "startDate");
    expect(v.days).toBe("");
    v = applyDependencies(meta, { ...v, endDate: "2026-07-05" }, "endDate");
    expect(v.days).toBe("5");
    v = applyDependencies(meta, { ...v, startDate: "2026-07-03" }, "startDate");
    expect(v.days).toBe("3");
    expect(validate(meta, { ...v, days: "" }).days).toBeUndefined();
  });
});

describe("the academic year", () => {
  const year = { name: "2025-26", start: "2025-06-01", end: "2026-05-31" };
  const f = (type: "DATE" | "MONTH_YEAR" | "INT") => ({ name: "x", label: "Date", type, required: true, maxLength: null, min: null, max: null, scale: null, allowed: null, inAcademicYear: true }) as const;

  it("accepts the first and last day and refuses the days around them", () => {
    expect(outsideYear(f("DATE"), "2025-06-01", year)).toBeNull();
    expect(outsideYear(f("DATE"), "2026-05-31", year)).toBeNull();
    expect(outsideYear(f("DATE"), "2025-05-31", year)).toBe("Date must be within the academic year 2025-26 (01-06-2025 to 31-05-2026).");
    expect(outsideYear(f("DATE"), "2026-06-01", year)).not.toBeNull();
  });

  it("works by month for a month and year", () => {
    expect(outsideYear(f("MONTH_YEAR"), "2025-06", year)).toBeNull();
    expect(outsideYear(f("MONTH_YEAR"), "2026-05", year)).toBeNull();
    expect(outsideYear(f("MONTH_YEAR"), "2025-05", year)).not.toBeNull();
    expect(outsideYear(f("MONTH_YEAR"), "2026-06", year)).not.toBeNull();
  });

  it("allows a bare year only if it is one of the two the academic year spans", () => {
    expect(outsideYear(f("INT"), "2025", year)).toBeNull();
    expect(outsideYear(f("INT"), "2026", year)).toBeNull();
    expect(outsideYear(f("INT"), "2024", year)).toBe("Date must be 2025 or 2026, the years of the academic year 2025-26.");
  });

  it("is applied by validate only to the fields that must be inside the year", () => {
    const m = { key: "d", singleton: false, uniqueField: null, dateRanges: [], fields: [f("DATE"), { ...f("DATE"), name: "y", inAcademicYear: false }] } as SectionMeta;
    expect(validate(m, { x: "2024-01-01", y: "2024-01-01" }, [], year)).toEqual({ x: "Date must be within the academic year 2025-26 (01-06-2025 to 31-05-2026)." });
    expect(validate(m, { x: "2024-01-01", y: "2024-01-01" })).toEqual({});
  });
});
