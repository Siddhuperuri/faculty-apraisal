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
    for (const s of sections) expect(shown.has(s.key), `${s.key} has no page`).toBe(true);
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

  it("radio buttons and field hints refer to real choice and text fields", () => {
    for (const p of PAGES)
      for (const ui of p.sections) {
        const fields = new Map(byKey.get(ui.key)!.fields.map((f) => [f.name, f]));
        for (const r of ui.radios ?? []) expect(fields.get(r)?.type, `${ui.key}: radio ${r}`).toBe("ENUM");
        for (const h of Object.keys(ui.fieldHints ?? {})) expect(fields.has(h), `${ui.key}: hint ${h}`).toBe(true);
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
    expect(f.get("semester")!.allowed).toHaveLength(12);
  });

  it("the administrative role fields say the role can be selected or entered", () => {
    const roles = PAGES.flatMap((p) => p.sections).filter((s) => s.key === "administrative-roles");
    expect(roles).toHaveLength(2);
    for (const ui of roles) expect(ui.fieldHints?.role).toBe("Select or enter your role");
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

  it("every allowed value has a readable label (no raw codes on screen)", () => {
    for (const s of sections)
      for (const f of s.fields)
        for (const v of f.allowed ?? []) {
          const label = enumLabel(v);
          expect(label, `${s.key}.${f.name}=${v}`).not.toBe("");
          // Codes with underscores (CO_PI, SCI_SCIE...) must never reach the screen as-is.
          if (v.includes("_")) expect(label, `${s.key}.${f.name}=${v}`).not.toContain("_");
        }
  });
});
