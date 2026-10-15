import { describe, expect, it } from "vitest";
import meta from "./__fixtures__/meta.json";
import { PAGES } from "./formStructure";
import { enumLabel } from "./labels";
import type { SectionMeta } from "./types";

/**
 * The UI refers to server sections and fields by name. These tests check every reference against
 * __fixtures__/meta.json, a snapshot of GET /api/sections/meta. The backend's FrontendContractTest fails
 * if the snapshot no longer matches the real server, so a renamed field cannot silently break a screen.
 */
const sections = meta as unknown as SectionMeta[];
const byKey = new Map(sections.map((s) => [s.key, s]));

describe("form structure vs server definitions", () => {
  it("every section the UI shows exists on the server", () => {
    for (const p of PAGES) for (const s of p.sections) expect(byKey.has(s.key), `${p.slug}: ${s.key}`).toBe(true);
  });

  it("every server section is shown on some page (nothing is unreachable)", () => {
    const shown = new Set(PAGES.flatMap((p) => p.sections.map((s) => s.key)));
    // The institutional roles are no longer on the form (2026-10-13) but the server still holds and scores what was entered.
    const notOnTheForm = new Set(["institutional-roles"]);
    for (const s of sections) expect(shown.has(s.key) || notOnTheForm.has(s.key), `${s.key} has no page`).toBe(true);
  });

  it("every table column and header refers to a real field", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        const fields = new Set(byKey.get(ui.key)!.fields.map((f) => f.name));
        for (const c of ui.columns ?? []) if (typeof c === "string") expect(fields.has(c), `${ui.key}: column ${c}`).toBe(true);
        for (const h of Object.keys(ui.headers ?? {})) expect(fields.has(h), `${ui.key}: header ${h}`).toBe(true);
        for (const f of Object.keys(ui.suggestions ?? {})) expect(fields.has(f), `${ui.key}: suggestions ${f}`).toBe(true);
      }
  });

  it("scoped views filter on a real choice field and a real value", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        if (!ui.scope) continue;
        const f = byKey.get(ui.key)!.fields.find((x) => x.name === ui.scope!.field);
        expect(f?.type, `${ui.key} scope field`).toBe("ENUM");
        expect(f!.allowed).toContain(ui.scope.value);
      }
  });

  it("the two administrative views together cover every scope value (no hidden records)", () => {
    const f = byKey.get("administrative-roles")!.fields.find((x) => x.name === "scope")!;
    const covered = PAGES.flatMap((p) => p.sections).filter((s) => s.key === "administrative-roles").map((s) => s.scope?.value);
    expect([...covered].sort()).toEqual([...f.allowed!].sort());
  });

  it("the fixed-grid section is keyed by a choice field", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        if (!ui.fixedBy) continue;
        const f = byKey.get(ui.key)!.fields.find((x) => x.name === ui.fixedBy);
        expect(f?.type).toBe("ENUM");
      }
  });

  it("gates refer to a real section, field and value", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        if (!ui.gate) continue;
        const f = byKey.get(ui.gate.section)?.fields.find((x) => x.name === ui.gate!.field);
        expect(f?.allowed).toContain(ui.gate.equals);
      }
  });

  it("radio buttons refer to real choice fields", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        const fields = new Map(byKey.get(ui.key)!.fields.map((f) => [f.name, f]));
        for (const r of ui.radios ?? []) expect(fields.get(r)?.type, `${ui.key}: radio ${r}`).toBe("ENUM");
      }
  });

  it("the course form asks for the role beside the course code, as a choice of two", () => {
    const course = byKey.get("teaching-courses")!;
    expect(course.fields.map((f) => f.name).slice(0, 2)).toEqual(["courseCode", "courseRole"]);
    const role = course.fields[1];
    expect(role.required).toBe(true);
    expect(role.allowed).toEqual(["COORDINATOR", "INSTRUCTOR"]);
    const ui = PAGES.flatMap((p) => p.sections).find((s) => s.key === "teaching-courses")!;
    expect(ui.radios).toContain("courseRole");
  });

  it("semester, section and hours are drop-downs with the allowed lists", () => {
    const f = new Map(byKey.get("teaching-courses")!.fields.map((x) => [x.name, x]));
    expect(f.get("section")!.allowed).toEqual(["A", "B", "C", "D", "E"]);
    expect(f.get("hoursPerWeek")!.allowed).toEqual(["1", "2", "3", "4", "5", "6"]);
    expect([f.get("hoursPerWeek")!.min, f.get("hoursPerWeek")!.max]).toEqual([1, 6]);
    expect(f.get("semester")!.allowed).toHaveLength(8);   // semesters 1 to 8 for every department
  });

  it("the administrative roles are two lists on the page, one for each level", () => {
    const roles = PAGES.flatMap((p) => p.sections).filter((s) => s.key === "administrative-roles");
    expect(roles).toHaveLength(2);
  });

  it("FDPs have a duration in days and no dates; certifications have weeks and a platform name for Other", () => {
    const fdp = byKey.get("fdps")!.fields.map((f) => f.name);
    expect(fdp).not.toContain("startDate");
    expect(fdp).not.toContain("endDate");
    expect(byKey.get("fdps")!.fields.find((f) => f.name === "days")!.required).toBe(true);
    const cert = byKey.get("certifications")!.fields;
    expect(cert.map((f) => f.name)).toEqual(expect.arrayContaining(["durationWeeks", "platformOther"]));
    expect(cert.map((f) => f.name)).not.toContain("durationWeeksHours");
    expect(cert.map((f) => f.name)).not.toContain("startDate");
    expect(cert.find((f) => f.name === "platformOther")).toMatchObject({ onlyWhenField: "platform", onlyWhenEquals: "OTHER" });
  });

  it("the date of promotion is optional and the conference papers are 'published'", () => {
    const promo = byKey.get("general-information")!.fields.find((f) => f.name === "joiningDateDesignation")!;
    expect(promo.label).toBe("Date of promotion");
    expect(promo.required).toBe(false);
    expect(promo.submitRequired).toBeFalsy();
    const titles = PAGES.flatMap((p) => p.sections.map((s) => s.title));
    expect(titles).toContain("Conference papers published");
    expect(titles).not.toContain("Conference papers presented");
  });

  it("courses are typed: Course and Course Code are two ordinary fields, semester is 1 to 8, and ECAP is not involved", () => {
    const ui = PAGES.flatMap((p) => p.sections).find((x) => x.key === "teaching-courses")!;
    expect("ecapCourse" in ui).toBe(false);
    const fields = byKey.get("teaching-courses")!.fields;
    expect(fields.find((f) => f.name === "courseName")).toMatchObject({ label: "Course", required: true });
    expect(fields.find((f) => f.name === "courseCode")).toMatchObject({ label: "Course Code", required: true });
    const semester = fields.find((f) => f.name === "semester")!;
    expect(semester.allowed).toEqual(["1", "2", "3", "4", "5", "6", "7", "8"]);
    expect(semester).toMatchObject({ min: 1, max: 8 });
  });

  it("the action plan is section 12: one text of at most 2,000 words, needed to submit", () => {
    const page = PAGES.find((p) => p.slug === "action-plan")!;
    expect(page.number).toBe("12");
    expect(page.title).toBe("Action Plan for the Next 2 Years");
    const plan = byKey.get("action-plan")!;
    expect(plan.singleton).toBe(true);
    const field = plan.fields.find((f) => f.name === "plan")!;
    expect(field).toMatchObject({ maxWords: 2000, submitRequired: true, required: false });
    expect(PAGES.map((p) => p.number)).toEqual(["01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"]);
  });

  it("institute and department roles, and the type and role of an event, are dropdowns that end in Other", () => {
    const roles = byKey.get("administrative-roles")!.fields;
    const code = roles.find((f) => f.name === "roleCode")!;
    expect(code.dependsOn).toBe("scope");
    expect(code.allowedBy!.INSTITUTE.at(-1)).toBe("OTHER");
    expect(code.allowedBy!.DEPARTMENT.at(-1)).toBe("OTHER");
    expect(roles.find((f) => f.name === "roleOther")).toMatchObject({ onlyWhenField: "roleCode", onlyWhenEquals: "OTHER" });
    expect(roles.find((f) => f.name === "role")!.effective).toBe(true);
    const events = byKey.get("events")!.fields;
    for (const f of ["activityCode", "roleCode"]) expect(events.find((x) => x.name === f)!.allowed!.at(-1)).toBe("OTHER");
    expect(events.find((x) => x.name === "activityOther")).toMatchObject({ onlyWhenField: "activityCode" });
  });

  it("the Teaching & Learning page shows the courses only: no institutional roles box", () => {
    const page = PAGES.find((p) => p.slug === "teaching")!;
    expect(page.sections.map((x) => x.key)).toEqual(["teaching-courses"]);
    expect(page.description.toLowerCase()).not.toContain("institutional role");
    // the roles themselves are still held and scored by the server, from a database list
    const role = byKey.get("institutional-roles")!.fields.find((f) => f.name === "role")!;
    expect(role.allowed).toEqual(expect.arrayContaining(["HOD", "ASSOCIATE_DEAN"]));
    // and no section on any page is listed twice under the same key without a scope
    for (const p of PAGES) expect(p.sections.every((s) => byKey.has(s.key)), p.slug).toBe(true);
  });

  it("teaching experience is whole years from 0 to 40 (required) and whole months from 0 to 11", () => {
    const years = byKey.get("general-information")!.fields.find((x) => x.name === "teachingExperienceYears")!;
    expect(years.required).toBe(true);
    expect([years.min, years.max]).toEqual([0, 40]);
    const months = byKey.get("general-information")!.fields.find((x) => x.name === "teachingExperienceMonths")!;
    expect([months.min, months.max]).toEqual([0, 11]);
  });

  it("research profile IDs are added one at a time, each kind once, and the older single text box is not shown", () => {
    const general = PAGES.find((p) => p.slug === "general")!;
    expect(general.sections.map((x) => x.key)).toEqual(["general-information", "research-profiles"]);
    expect(general.sections[0].hiddenFields).toEqual(["researchIds"]);
    const platform = byKey.get("research-profiles")!.fields.find((x) => x.name === "platform")!;
    expect(platform.allowed).toEqual(expect.arrayContaining(["ORCID", "SCOPUS", "GOOGLE_SCHOLAR"]));
    expect(byKey.get("research-profiles")!.uniqueField).toBe("platform");
  });

  it("a journal paper needs a DOI or ISSN", () => {
    const f = byKey.get("journal-publications")!.fields.find((x) => x.name === "doiIssn")!;
    expect(f.required).toBe(true);
    expect(f.format).toBe("DOI_ISSN");
  });

  it("a conference paper has no citation count, on the form or in its table", () => {
    expect(byKey.get("conference-papers")!.fields.map((f) => f.name)).not.toContain("citations");
    const ui = PAGES.flatMap((p) => p.sections).find((s) => s.key === "conference-papers")!;
    expect(JSON.stringify(ui.columns)).not.toContain("citations");
    expect(JSON.stringify(ui.headers)).not.toContain("Citations");
  });

  it("a project's amount follows its status: asked for when sanctioned, greyed out and empty otherwise", () => {
    const amount = byKey.get("funded-projects")!.fields.find((f) => f.name === "amount")!;
    expect(amount).toMatchObject({ onlyWhenField: "status", onlyWhenEquals: "SANCTIONED", onlyWhenDisabled: true, format: "CURRENCY", required: false });
  });

  it("a chapter is a Book Chapter on screen and an outreach role can be Other with a typed role", () => {
    expect(byKey.get("books")!.fields.find((f) => f.name === "type")!.labels?.CHAPTER).toBe("Book Chapter");
    const role = byKey.get("outreach")!.fields.find((f) => f.name === "roleCode")!;
    expect(role.labels?.EXTERNAL_EXAMINER).toBe("Project External Examiner");
    expect(role.allowed!.at(-1)).toBe("OTHER");
    expect(byKey.get("outreach")!.fields.find((f) => f.name === "roleOther")).toMatchObject({ onlyWhenField: "roleCode", onlyWhenEquals: "OTHER", onlyWhenDisabled: false });
    expect(byKey.get("outreach")!.fields.find((f) => f.name === "role")!.effective).toBe(true);
  });

  it("funded projects are not limited in number, an ISBN is required, and institute roles come before department roles", () => {
    const funded = PAGES.flatMap((p) => p.sections).find((x) => x.key === "funded-projects")!;
    expect(funded.maxRecords).toBeUndefined();
    expect(byKey.get("books")!.fields.find((f) => f.name === "isbn")!.required).toBe(true);
    const roles = PAGES.find((p) => p.slug === "administration")!.sections.filter((x) => x.key === "administrative-roles");
    expect(roles.map((x) => x.scope?.value)).toEqual(["INSTITUTE", "DEPARTMENT"]);
  });

  it("every value of a list-backed field comes with its label from the server", () => {
    for (const s of sections)
      for (const f of s.fields) {
        if (f.type === "ENUM" && f.name !== "scope") {
          expect(f.labels, `${s.key}.${f.name} has labels`).toBeTruthy();
          for (const v of f.allowed ?? []) expect(f.labels![v], `${s.key}.${f.name}=${v}`).toBeTruthy();
        }
      }
  });

  it("every allowed value has a readable label (no raw codes on screen)", () => {
    for (const s of sections)
      for (const f of s.fields)
        for (const v of f.allowed ?? []) {
          const label = f.labels?.[v] ?? enumLabel(v);
          expect(label, `${s.key}.${f.name}=${v}`).not.toBe("");
          // Codes with underscores (CO_PI, SCI_SCIE...) must never reach the screen as-is.
          if (v.includes("_")) expect(label, `${s.key}.${f.name}=${v}`).not.toContain("_");
        }
  });
});
