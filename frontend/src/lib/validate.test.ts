import { describe, expect, it } from "vitest";
import type { FieldMeta, SectionMeta } from "./types";
import { applyDependencies, countWords, hasValidDoiOrIssn, isAsked, isBlank, liveFieldError, serverErrorsFor, toApiRecord, toFormValues, validate, validIsbn } from "./validate";

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

describe("immediate validation (as the person types)", () => {
  const passPercentage = {
    name: "passPercentage",
    label: "Pass percentage",
    type: "DECIMAL",
    required: true,
    maxLength: null,
    min: 0,
    max: 100,
    scale: 2,
    allowed: null,
  } as FieldMeta;

  it("names the range as soon as 120 is typed, in the server's words", () => {
    expect(liveFieldError(passPercentage, "120", true)).toBe("Pass percentage must be between 0 and 100.");
    expect(liveFieldError(passPercentage, "92.5", true)).toBeNull();
    expect(liveFieldError(passPercentage, "-1", true)).toBe("Pass percentage must be between 0 and 100.");
    expect(liveFieldError(passPercentage, "9x", true)).toBe("Pass percentage must be a number.");
    expect(liveFieldError(passPercentage, "90.123", true)).toBe("Pass percentage can have at most 2 decimal places.");
  });

  it("an empty required field is a problem; an empty optional one is not", () => {
    expect(liveFieldError(passPercentage, "  ", true)).toBe("Pass percentage is required.");
    expect(liveFieldError({ ...passPercentage, required: false }, "", false)).toBeNull();
  });

  it("a semester must be 1 to 8, whole", () => {
    const semester = { ...passPercentage, name: "semester", label: "Semester", type: "INT", scale: null, min: 1, max: 8, allowed: ["1", "2", "3", "4", "5", "6", "7", "8"] } as FieldMeta;
    expect(liveFieldError(semester, "9", true)).toBe("Semester must be one of: 1, 2, 3, 4, 5, 6, 7, 8.");
    expect(liveFieldError(semester, "2.5", true)).toBe("Semester must be a whole number.");
    expect(liveFieldError(semester, "8", true)).toBeNull();
  });

  it("a date outside the academic year says so", () => {
    const when = { name: "fromDate", label: "From date", type: "DATE", required: true, maxLength: null, min: null, max: null, scale: null, allowed: null, inAcademicYear: true } as FieldMeta;
    const year = { name: "2025-26", start: "2025-06-01", end: "2026-05-31" };
    expect(liveFieldError(when, "2024-01-01", true, year)).toContain("must be within the academic year 2025-26");
    expect(liveFieldError(when, "2025-09-01", true, year)).toBeNull();
    expect(liveFieldError(when, "not a date", true, year)).toBe("From date must be a valid date.");
  });

  it("a word limit counts words, not characters, and says how many there are", () => {
    const plan = { name: "plan", label: "Action plan", type: "TEXT", required: false, maxLength: 80000, maxWords: 2000, min: null, max: null, scale: null, allowed: null } as FieldMeta;
    expect(liveFieldError(plan, Array(2000).fill("word").join(" "), false)).toBeNull();
    expect(liveFieldError(plan, Array(2001).fill("word").join(" "), false)).toBe("Action plan must be at most 2,000 words (it has 2,001).");
    expect(liveFieldError(plan, "", false)).toBeNull();            // a draft may leave it empty
    expect(countWords("  one two\nthree\t four  ")).toBe(4);
    expect(countWords("   ")).toBe(0);
  });

  it("a dependent choice is judged against what the choice above offers", () => {
    const branch = { name: "branch", label: "Branch", type: "ENUM", required: true, maxLength: null, min: null, max: null, scale: null, allowed: ["CSE", "ECE"], dependsOn: "program" } as FieldMeta;
    expect(liveFieldError(branch, "PHARMACOLOGY", true)).toBe("Branch is not offered under the choice above.");
    expect(liveFieldError(branch, "CSE", true)).toBeNull();
  });
});

describe("formats checked as the person types", () => {
  const base = { required: false, min: null, max: null, scale: null, allowed: null } as const;
  const doi = { ...base, name: "doiIssn", label: "DOI or ISSN", type: "TEXT", required: true, maxLength: 80, format: "DOI_ISSN" } as FieldMeta;
  const isbn = { ...base, name: "isbn", label: "ISBN", type: "TEXT", required: true, maxLength: 24, format: "ISBN" } as FieldMeta;
  const amount = { ...base, name: "amount", label: "Amount (Rs.)", type: "DECIMAL", min: 0, max: 1e11, scale: 2, maxLength: null, format: "CURRENCY" } as FieldMeta;

  it("a DOI or ISSN field needs at least one valid value, as the server counts it", () => {
    expect(liveFieldError(doi, "", true)).toBe("DOI or ISSN is required.");
    const message = "DOI or ISSN must include a valid DOI (such as 10.1000/xyz123) or ISSN (such as 1234-5678).";
    for (const bad of ["n/a", "none", "12345678", "0378-5954", "10.12/short", "ISSN:"]) expect(liveFieldError(doi, bad, true), bad).toBe(message);
    for (const good of ["10.1000/xyz123", "0378-5955", "2434-561X", "doi:10.1016/j.cell.2020.01.001", "https://doi.org/10.1109/5.771073", "ISSN: 0378-5955", "10.1000/abc, 0378-5955"]) {
      expect(liveFieldError(doi, good, true), good).toBeNull();
    }
    expect(hasValidDoiOrIssn("1050-124X")).toBe(true);
    expect(hasValidDoiOrIssn("1050-124Y")).toBe(false);
  });

  it("an ISBN needs its check digit", () => {
    expect(validIsbn("978-0-13-468599-1")).toBe(true);
    expect(validIsbn("0-306-40615-2")).toBe(true);
    expect(validIsbn("978-0-13-468599-2")).toBe(false);
    expect(liveFieldError(isbn, "123", true)).toBe("ISBN must be a valid ISBN-10 or ISBN-13 (for example 978-0-13-468599-1).");
    expect(liveFieldError(isbn, "9780134685991", true)).toBeNull();
  });

  it("an amount is 0 or more with at most two decimal places", () => {
    const message = "Amount (Rs.) must be a valid amount: 0 or more, with at most 2 decimal places.";
    for (const bad of ["-1", "-0.01", "abc", "12.345", "1,000"]) expect(liveFieldError(amount, bad, false), bad).toBe(message);
    for (const good of ["0", "250000.50", "40000", "12.3"]) expect(liveFieldError(amount, good, false), good).toBeNull();
    expect(liveFieldError(amount, "100000000001", false)).toBe("Amount (Rs.) must be at most 100000000000.");
    expect(liveFieldError(amount, "", false)).toBeNull();
  });
});

describe("a field that depends on another choice", () => {
  const base = { min: null, max: null, scale: null, allowed: null, maxLength: null } as const;
  const projects: SectionMeta = {
    key: "funded-projects",
    singleton: false,
    uniqueField: null,
    dateRanges: [],
    fields: [
      { ...base, name: "status", label: "Status", type: "ENUM", required: true, allowed: ["SANCTIONED", "APPLIED"], labels: { SANCTIONED: "Sanctioned", APPLIED: "Applied" } },
      { ...base, name: "amount", label: "Amount (Rs.)", type: "DECIMAL", required: false, scale: 2, min: 0, max: 1e11, format: "CURRENCY", onlyWhenField: "status", onlyWhenEquals: "SANCTIONED", onlyWhenDisabled: true },
    ] as FieldMeta[],
  };

  it("the amount is required when the project is sanctioned, in the server's words", () => {
    expect(validate(projects, { status: "SANCTIONED", amount: "" }).amount).toBe("Amount (Rs.) is required when Status is Sanctioned.");
    expect(validate(projects, { status: "SANCTIONED", amount: "5000" })).toEqual({});
  });

  it("it is not asked, not checked and sent as empty while the project is only applied for", () => {
    expect(validate(projects, { status: "APPLIED", amount: "" })).toEqual({});
    expect(validate(projects, { status: "APPLIED", amount: "garbage" })).toEqual({});
    expect(toApiRecord(projects, { status: "APPLIED", amount: "5000" }).amount).toBeNull();
    expect(toApiRecord(projects, { status: "SANCTIONED", amount: "5000" }).amount).toBe("5000");
  });

  it("changing the status to applied clears what was typed", () => {
    expect(applyDependencies(projects, { status: "APPLIED", amount: "5000" }, "status").amount).toBe("");
    expect(applyDependencies(projects, { status: "SANCTIONED", amount: "5000" }, "status").amount).toBe("5000");
  });
});
