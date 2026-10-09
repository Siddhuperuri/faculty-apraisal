import { describe, expect, it } from "vitest";
import { ADD_COURSE_TARGET, buildRequirements } from "./requirements";
import type { FieldMeta, ScoreRow, SectionMeta } from "./types";

const field = (name: string, label: string, submitRequired = false): FieldMeta =>
  ({ name, label, type: "TEXT", required: false, submitRequired, maxLength: 50, min: null, max: null, scale: null, allowed: null }) as FieldMeta;

const meta = {
  key: "general-information",
  singleton: true,
  uniqueField: null,
  dateRanges: [],
  fields: [field("contactNo", "Contact number", true), field("qualification", "Qualification", true), field("notes", "Notes")],
} as SectionMeta;

const score = (criterion: string, label: string, maxMarks: number | null, value: number | null) => ({ criterion, label, maxMarks, score: value }) as ScoreRow;

describe("buildRequirements", () => {
  it("lists the Part A fields that are still empty, each as a link to the field", () => {
    const [r] = buildRequirements({
      blockers: ["General Information: contact number, ..."],
      scores: [],
      general: { meta, record: { contactNo: "9876543210", qualification: " " } },
    });
    expect(r.page).toBe("general");
    expect(r.summary).toBe("1 detail needs filling in");
    expect(r.links).toEqual([{ label: "Qualification", focus: "qualification" }]);
  });

  it("falls back to a link to the page while Part A is still loading", () => {
    const [r] = buildRequirements({ blockers: ["General Information: x"], scores: [], general: { meta: undefined, record: undefined } });
    expect(r.links).toEqual([{ label: "Open the page", focus: null }]);
  });

  it("says how many more courses are needed and links to the Add button", () => {
    const [r] = buildRequirements({
      blockers: ["Teaching & Learning: at least 8 courses handled (you have 6)"],
      scores: [],
      general: { meta, record: null },
    });
    expect(r.page).toBe("teaching");
    expect(r.summary).toBe("2 more courses needed (6 of 8 added)");
    expect(r.links).toEqual([{ label: "Add a course", focus: ADD_COURSE_TARGET }]);
    expect(buildRequirements({ blockers: ["Teaching & Learning: at least 8 courses handled (you have 7)"], scores: [], general: { meta, record: null } })[0].summary).toBe("1 more course needed (7 of 8 added)");
  });

  it("links each missing self-score, skipping criteria with no marks and those already scored", () => {
    const [r] = buildRequirements({
      blockers: ["Self-scores for: A; B"],
      scores: [score("A", "Criterion A", 10, null), score("B", "Criterion B", null, null), score("C", "Criterion C", 0, null), score("D", "Criterion D", 5, 3)],
      general: { meta, record: null },
    });
    expect(r.page).toBeNull();
    expect(r.summary).toBe("2 self-scores are missing");
    expect(r.links).toEqual([
      { label: "Criterion A", focus: "score-A" },
      { label: "Criterion B", focus: "score-B" },
    ]);
  });

  it("shows a line it does not recognise as the server wrote it", () => {
    const [r] = buildRequirements({ blockers: ["Something new"], scores: [], general: { meta, record: null } });
    expect(r).toMatchObject({ heading: "Other", summary: "Something new", links: [] });
  });

  it("returns nothing when nothing is missing", () => {
    expect(buildRequirements({ blockers: [], scores: [], general: { meta, record: null } })).toEqual([]);
  });
});
