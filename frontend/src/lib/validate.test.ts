import { describe, expect, it } from "vitest";
import type { SectionMeta } from "./types";
import { applyDependencies, isAsked, isBlank, serverErrorsFor, toApiRecord, toFormValues, validate } from "./validate";

const meta: SectionMeta = {
  key: "demo",
  singleton: false,
  uniqueField: null,
  dateRanges: [{ startField: "startDate", endField: "endDate" }],
  fields: [
    { name: "title", label: "Title", type: "TEXT", required: true, maxLength: 10, min: null, max: null, scale: null, allowed: null },
    { name: "count", label: "Number of students", type: "INT", required: true, min: 0, max: 100, maxLength: null, scale: null, allowed: null },
    { name: "pass", label: "Pass percentage", type: "DECIMAL", required: false, min: 0, max: 100, scale: 2, maxLength: null, allowed: null },
    { name: "mode", label: "Mode", type: "ENUM", required: true, allowed: ["ONLINE", "OFFLINE"], maxLength: null, min: null, max: null, scale: null },
    { name: "startDate", label: "Start date", type: "DATE", required: true, maxLength: null, min: null, max: null, scale: null, allowed: null },
    { name: "endDate", label: "End date", type: "DATE", required: true, maxLength: null, min: null, max: null, scale: null, allowed: null },
    { name: "month", label: "Month and year", type: "MONTH_YEAR", required: false, maxLength: null, min: null, max: null, scale: null, allowed: null },
  ],
};

const valid = { title: "Intro", count: "12", pass: "92.5", mode: "ONLINE", startDate: "2026-07-01", endDate: "2026-07-05", month: "2026-05" };

describe("validate", () => {
  it("accepts a valid record", () => {
    expect(validate(meta, valid)).toEqual({});
  });

  it("reports required fields in plain language", () => {
    const e = validate(meta, { ...valid, title: " ", mode: "" });
    expect(e.title).toBe("Title is required.");
    expect(e.mode).toBe("Mode is required.");
  });

  it("does not require optional fields", () => {
    expect(validate(meta, { ...valid, pass: "", month: "" })).toEqual({});
  });

  it("checks text length", () => {
    expect(validate(meta, { ...valid, title: "x".repeat(11) }).title).toBe("Title must be at most 10 characters.");
  });

  it("checks whole numbers and ranges", () => {
    expect(validate(meta, { ...valid, count: "1.5" }).count).toBe("Number of students must be a whole number.");
    expect(validate(meta, { ...valid, count: "abc" }).count).toBe("Number of students must be a whole number.");
    expect(validate(meta, { ...valid, count: "101" }).count).toBe("Number of students must be between 0 and 100.");
    expect(validate(meta, { ...valid, count: "-1" }).count).toBe("Number of students must be between 0 and 100.");
  });

  it("checks decimals, places and range", () => {
    expect(validate(meta, { ...valid, pass: "101" }).pass).toBe("Pass percentage must be between 0 and 100.");
    expect(validate(meta, { ...valid, pass: "90.123" }).pass).toBe("Pass percentage can have at most 2 decimal places.");
    expect(validate(meta, { ...valid, pass: "90.10" }).pass).toBeUndefined(); // trailing zero is not a third place
    expect(validate(meta, { ...valid, pass: "x" }).pass).toBe("Pass percentage must be a number.");
  });

  it("checks choices, dates and month-year", () => {
    expect(validate(meta, { ...valid, mode: "BLENDED" }).mode).toBe("Mode must be one of the listed options.");
    expect(validate(meta, { ...valid, startDate: "01/07/2026" }).startDate).toBe("Start date must be a valid date.");
    expect(validate(meta, { ...valid, month: "2026-13" }).month).toBe("Month and year must be a month and year.");
  });

  it("rejects an end date before the start date, with the server's wording", () => {
    expect(validate(meta, { ...valid, endDate: "2026-06-30" }).endDate).toBe("End date must not be before start date.");
  });

  it("skips hidden fields", () => {
    expect(validate(meta, { ...valid, mode: "" }, ["mode"]).mode).toBeUndefined();
  });
});

describe("conversion", () => {
  it("turns form strings into the API shape", () => {
    const rec = toApiRecord(meta, { ...valid, pass: "", month: "" }, 7);
    expect(rec).toEqual({ id: 7, title: "Intro", count: 12, pass: null, mode: "ONLINE", startDate: "2026-07-01", endDate: "2026-07-05", month: null });
  });

  it("sends decimals as typed text so no floating-point noise is introduced", () => {
    expect(toApiRecord(meta, valid).pass).toBe("92.5");
  });

  it("builds form values from a saved record and presets new ones", () => {
    expect(toFormValues(meta, { id: 1, title: "A", count: 3, pass: 4.5, mode: null })).toMatchObject({ title: "A", count: "3", pass: "4.5", mode: "" });
    expect(toFormValues(meta, null, { mode: "LAB" }).mode).toBe("LAB");
  });

  it("detects a completely blank form", () => {
    expect(isBlank({ a: "", b: "  " })).toBe(true);
    expect(isBlank({ a: "", b: "x" })).toBe(false);
  });
});

describe("serverErrorsFor", () => {
  it("maps server keys for one record back to field names", () => {
    const server = { "records[0].title": "bad", "records[1].count": "worse", "records[1].mode": "x" };
    expect(serverErrorsFor(server, 1)).toEqual({ count: "worse", mode: "x" });
    expect(serverErrorsFor(undefined, 0)).toEqual({});
  });
});

describe("a number chosen from a list", () => {
  const hours: SectionMeta = {
    key: "demo", singleton: false, uniqueField: null, dateRanges: [],
    fields: [
      { name: "hoursPerWeek", label: "Hours per week", type: "DECIMAL", required: true, min: 1, max: 6, scale: 0, maxLength: null, allowed: ["1", "2", "3", "4", "5", "6"] },
      { name: "semester", label: "Semester", type: "INT", required: true, min: 1, max: 12, scale: null, maxLength: null, allowed: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"] },
    ],
  };

  it("accepts only the listed values", () => {
    expect(validate(hours, { hoursPerWeek: "6", semester: "12" })).toEqual({});
    expect(validate(hours, { hoursPerWeek: "1", semester: "1" })).toEqual({});
    expect(validate(hours, { hoursPerWeek: "7", semester: "3" }).hoursPerWeek).toBe("Hours per week must be one of: 1, 2, 3, 4, 5, 6.");
    expect(validate(hours, { hoursPerWeek: "0", semester: "3" }).hoursPerWeek).toBe("Hours per week must be one of: 1, 2, 3, 4, 5, 6.");
    expect(validate(hours, { hoursPerWeek: "2.5", semester: "3" }).hoursPerWeek).toBe("Hours per week must be one of: 1, 2, 3, 4, 5, 6.");
    expect(validate(hours, { hoursPerWeek: "", semester: "3" }).hoursPerWeek).toBe("Hours per week is required.");
    expect(validate(hours, { hoursPerWeek: "3", semester: "13" }).semester).toContain("must be one of");
  });
});

describe("a field asked for only under another choice", () => {
  const cert: SectionMeta = {
    key: "certifications", singleton: false, uniqueField: null, dateRanges: [],
    fields: [
      { name: "platform", label: "Platform", type: "ENUM", required: true, allowed: ["NPTEL", "OTHER"], maxLength: null, min: null, max: null, scale: null },
      { name: "platformOther", label: "Name of the platform", type: "TEXT", required: false, maxLength: 100, min: null, max: null, scale: null, allowed: null,
        onlyWhenField: "platform", onlyWhenEquals: "OTHER" },
    ],
  };

  it("is required only while the platform is Other", () => {
    expect(validate(cert, { platform: "NPTEL", platformOther: "" })).toEqual({});
    expect(validate(cert, { platform: "OTHER", platformOther: "  " }).platformOther).toBe("Name of the platform is required when Platform is Other.");
    expect(validate(cert, { platform: "OTHER", platformOther: "Infosys Springboard" })).toEqual({});
  });

  it("is not sent, and is cleared, when the platform is not Other", () => {
    expect(isAsked(cert.fields[1], { platform: "NPTEL", platformOther: "x" })).toBe(false);
    expect(toApiRecord(cert, { platform: "NPTEL", platformOther: "left over" }).platformOther).toBeNull();
    expect(toApiRecord(cert, { platform: "OTHER", platformOther: "Infosys Springboard" }).platformOther).toBe("Infosys Springboard");
    expect(applyDependencies(cert, { platform: "NPTEL", platformOther: "left over" }, "platform").platformOther).toBe("");
    expect(applyDependencies(cert, { platform: "OTHER", platformOther: "kept" }, "platform").platformOther).toBe("kept");
  });
});
