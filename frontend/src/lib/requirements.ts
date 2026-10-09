import type { Rec, ScoreRow, SectionMeta } from "./types";

/** One thing to jump to: the label shown, and the id or field name of the control to focus on arrival. */
export interface RequirementLink {
  label: string;
  focus: string | null;
}

/** One line of the "before you can submit" summary: where it is, what is missing, and links to the fields. */
export interface Requirement {
  key: string;
  /** Form page slug the links lead to; null for Score & Review. */
  page: string | null;
  heading: string;
  summary: string;
  links: RequirementLink[];
}

export interface RequirementInput {
  /** The server's own list of what is still missing (`submitBlockers`); this is the authority. */
  blockers: string[];
  scores: ScoreRow[];
  /** Part A's field descriptions and saved record, once loaded. */
  general: { meta: SectionMeta | undefined; record: Rec | undefined | null };
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const blank = (v: unknown) => v == null || String(v).trim() === "";

/** The id of the "Add course" button, which the Teaching page renders. */
export const ADD_COURSE_TARGET = "add-teaching-courses";

/**
 * Turns the server's missing-items list into a section-by-section summary with a link to each field. The server
 * decides what is missing; this only says where it is. A line the server adds that is not recognised is shown as written.
 */
export function buildRequirements({ blockers, scores, general }: RequirementInput): Requirement[] {
  return blockers.map((text, i): Requirement => {
    if (text.startsWith("General Information")) {
      const missing = general.meta && general.record !== undefined
        ? general.meta.fields.filter((f) => f.submitRequired && blank(general.record?.[f.name]))
        : null;
      return {
        key: `general-${i}`,
        page: "general",
        heading: "General Information",
        summary: missing ? `${plural(missing.length, "detail needs", "details need")} filling in` : "Some details need filling in",
        links: missing && missing.length > 0 ? missing.map((f) => ({ label: f.label, focus: f.name })) : [{ label: "Open the page", focus: null }],
      };
    }

    const courses = /^Teaching & Learning: at least (\d+) courses handled \(you have (\d+)\)/.exec(text);
    if (courses) {
      const need = Number(courses[1]);
      const have = Number(courses[2]);
      return {
        key: `teaching-${i}`,
        page: "teaching",
        heading: "Teaching & Learning",
        summary: `${plural(need - have, "more course", "more courses")} needed (${have} of ${need} added)`,
        links: [{ label: "Add a course", focus: ADD_COURSE_TARGET }],
      };
    }

    if (text.startsWith("Self-scores for")) {
      // Same rule as the server: a score is expected wherever the cadre has marks to give (or no maximum), and none is entered.
      const unscored = scores.filter((s) => (s.maxMarks === null || s.maxMarks > 0) && s.score === null);
      return {
        key: `scores-${i}`,
        page: null,
        heading: "Score sheet",
        summary: `${plural(unscored.length, "self-score is", "self-scores are")} missing`,
        links: unscored.length > 0 ? unscored.map((s) => ({ label: s.label, focus: `score-${s.criterion}` })) : [{ label: "Open the score sheet", focus: null }],
      };
    }

    return { key: `other-${i}`, page: null, heading: "Other", summary: text, links: [] };
  });
}
