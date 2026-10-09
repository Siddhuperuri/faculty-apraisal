import { describe, expect, it } from "vitest";
import { buildRequirements } from "./requirements";
import type { FieldMeta, SectionMeta } from "./types";

const field = (name: string, label: string, submitRequired = false): FieldMeta =>
  ({ name, label, type: "TEXT", required: false, submitRequired, maxLength: 50, min: null, max: null, scale: null, allowed: null }) as FieldMeta;

const meta = {
  key: "general-information",
  singleton: true,
  uniqueField: null,
  dateRanges: [],
  fields: [field("contactNo", "Contact number", true), field("qualification", "Qualification", true), field("notes", "Notes")],
} as SectionMeta;

describe("buildRequirements", () => {
  it("lists the Part A fields that are still empty, each as a link to the field", () => {
    const [r] = buildRequirements({
      blockers: ["General Information: contact number, ..."],
      general: { meta, record: { contactNo: "9876543210", qualification: " " } },
    });
    expect(r.page).toBe("general");
    expect(r.summary).toBe("1 detail needs filling in");
    expect(r.links).toEqual([{ label: "Qualification", focus: "qualification" }]);
  });

  it("falls back to a link to the page while Part A is still loading", () => {
    const [r] = buildRequirements({ blockers: ["General Information: x"], general: { meta: undefined, record: undefined } });
    expect(r.links).toEqual([{ label: "Open the page", focus: null }]);
  });

  it("shows a line it does not recognise as the server wrote it", () => {
    const [r] = buildRequirements({ blockers: ["Something new"], general: { meta, record: null } });
    expect(r).toMatchObject({ heading: "Other", summary: "Something new", links: [] });
  });

  it("returns nothing when nothing is missing", () => {
    expect(buildRequirements({ blockers: [], general: { meta, record: null } })).toEqual([]);
  });
});
