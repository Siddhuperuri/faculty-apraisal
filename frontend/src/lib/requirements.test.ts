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

describe("buildRequirements: the action plan", () => {
  it("sends the person to section 12 with a link to the text", () => {
    const [r] = buildRequirements({
      blockers: ["Action Plan for the Next 2 Years: write your plan (at most 2,000 words)"],
      general: { meta, record: null },
    });
    expect(r).toMatchObject({
      page: "action-plan",
      heading: "Action Plan for the Next 2 Years",
      summary: "write your plan (at most 2,000 words)",
      links: [{ label: "Open the page", focus: "plan" }],
    });
  });
});

describe("buildRequirements: teaching experience and journal papers (2026-10-13)", () => {
  it("lists a required Part A field that is empty, such as the teaching experience", () => {
    const withExperience = {
      ...meta,
      fields: [
        ...meta.fields,
        { name: "teachingExperienceYears", label: "Teaching experience (years)", type: "DECIMAL", required: true, maxLength: null, min: 0, max: 1000, scale: 1, allowed: null } as FieldMeta,
      ],
    } as SectionMeta;
    const [r] = buildRequirements({
      blockers: ["General Information: contact number, ..., and teaching experience"],
      general: { meta: withExperience, record: { contactNo: "9876543210", qualification: "M.Tech", teachingExperienceYears: null } },
    });
    expect(r.links).toEqual([{ label: "Teaching experience (years)", focus: "teachingExperienceYears" }]);
  });

  it("sends the person to the research page for a paper without a DOI or ISSN", () => {
    const [r] = buildRequirements({
      blockers: ["Journal publications: give a valid DOI or ISSN for the paper that lack one"],
      general: { meta, record: null },
    });
    expect(r).toMatchObject({ page: "research", heading: "Journal publications", links: [{ label: "Open the page", focus: null }] });
  });
});
