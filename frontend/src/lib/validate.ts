import { formatDate } from "./format";
import type { FieldMeta, Rec, SectionMeta } from "./types";

/**
 * Form values are held as strings (what inputs produce). These helpers convert to and from the API shape and
 * pre-validate with the server's own limits and wording, so people get immediate feedback. The server still
 * validates everything and its messages are shown if it disagrees.
 */
export type FormValues = Record<string, string>;
export type FieldErrors = Record<string, string>;

export function toFormValues(meta: SectionMeta, rec?: Rec | null, preset?: Record<string, string>): FormValues {
  const out: FormValues = {};
  for (const f of meta.fields) {
    const raw = rec ? rec[f.name] : undefined;
    out[f.name] = raw == null ? "" : String(raw);
    if (preset && f.name in preset && !rec) out[f.name] = preset[f.name];
  }
  return out;
}

export function isBlank(values: FormValues): boolean {
  return Object.values(values).every((v) => v.trim() === "");
}

function convert(f: FieldMeta, raw: string): unknown {
  const v = raw.trim();
  if (v === "") return null;
  if (f.type === "INT") return Number(v);
  return v; // DECIMAL goes as the typed text so no floating-point noise is introduced
}

export function toApiRecord(meta: SectionMeta, values: FormValues, id?: number): Rec {
  const rec: Rec = {};
  if (id != null) rec.id = id;
  for (const f of meta.fields) rec[f.name] = isAsked(f, values) ? convert(f, values[f.name] ?? "") : null;
  return rec;
}

/** False for a field that is asked only under another choice (a platform name when the platform is Other) and is not now. */
export function isAsked(f: FieldMeta, values: FormValues): boolean {
  return !f.onlyWhenField || (values[f.onlyWhenField] ?? "") === f.onlyWhenEquals;
}

const num = (s: string) => (s.trim() === "" || Number.isNaN(Number(s)) ? null : Number(s));

function fieldError(f: FieldMeta, raw: string, allowed: string[] | null = f.allowed): string | null {
  const v = raw.trim();
  if (v === "") return f.required ? `${f.label} is required.` : null;
  switch (f.type) {
    case "TEXT":
      return f.maxLength != null && v.length > f.maxLength ? `${f.label} must be at most ${f.maxLength} characters.` : null;
    case "INT": {
      const n = num(v);
      if (n == null || !Number.isInteger(n)) return `${f.label} must be a whole number.`;
      if (f.allowed?.length && !f.allowed.includes(String(n))) return `${f.label} must be one of: ${f.allowed.join(", ")}.`;
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return `${f.label} must be between ${f.min} and ${f.max}.`;
      return null;
    }
    case "DECIMAL": {
      const n = num(v);
      if (n == null) return `${f.label} must be a number.`;
      if (f.allowed?.length && !f.allowed.includes(String(n))) return `${f.label} must be one of: ${f.allowed.join(", ")}.`;
      const dp = (v.split(".")[1] ?? "").replace(/0+$/, "").length;
      if (f.scale != null && dp > f.scale) return `${f.label} can have at most ${f.scale} decimal places.`;
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return `${f.label} must be between ${f.min} and ${f.max}.`;
      return null;
    }
    case "ENUM":
      return allowed?.includes(v) ? null : f.dependsOn ? `${f.label} is not offered under the choice above.` : `${f.label} must be one of the listed options.`;
    case "DATE":
      return /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) ? null : `${f.label} must be a valid date.`;
    case "MONTH_YEAR":
      return /^\d{4}-(0[1-9]|1[0-2])$/.test(v) ? null : `${f.label} must be a month and year.`;
  }
}

/** The academic year an appraisal is for: its name and first and last day (YYYY-MM-DD). */
export interface YearBounds {
  name: string;
  start: string;
  end: string;
}

/** What is wrong with a day, month or year that must fall in the academic year, in the server's words; null when it does. */
export function outsideYear(f: FieldMeta, v: string, year: YearBounds): string | null {
  const within = `${f.label} must be within the academic year ${year.name} (${formatDate(year.start)} to ${formatDate(year.end)}).`;
  if (f.type === "DATE") return v < year.start || v > year.end ? within : null;
  if (f.type === "MONTH_YEAR") return v < year.start.slice(0, 7) || v > year.end.slice(0, 7) ? within : null;
  if (f.type === "INT") {
    const a = Number(year.start.slice(0, 4));
    const b = Number(year.end.slice(0, 4));
    return Number(v) < a || Number(v) > b ? `${f.label} must be ${a === b ? a : `${a} or ${b}`}, the years of the academic year ${year.name}.` : null;
  }
  return null;
}

/** Errors keyed by field name. Empty object means the values can be saved. */
export function validate(meta: SectionMeta, values: FormValues, hidden: string[] = [], year?: YearBounds): FieldErrors {
  const errors: FieldErrors = {};
  for (const f of meta.fields) {
    if (hidden.includes(f.name) || f.derived || !isAsked(f, values)) continue;   // a derived field is worked out, not typed
    if (f.onlyWhenField && (values[f.name] ?? "").trim() === "") {
      const parent = meta.fields.find((x) => x.name === f.onlyWhenField);
      errors[f.name] = `${f.label} is required when ${parent?.label ?? "the choice above"} is Other.`;
      continue;
    }
    const e = fieldError(f, values[f.name] ?? "", f.dependsOn ? (f.allowedBy?.[values[f.dependsOn] ?? ""] ?? []) : f.allowed);
    if (e) errors[f.name] = e;
    else if (year && f.inAcademicYear && (values[f.name] ?? "").trim() !== "") {
      const outside = outsideYear(f, values[f.name].trim(), year);
      if (outside) errors[f.name] = outside;
    }
  }
  for (const r of meta.dateRanges) {
    const a = values[r.startField]?.trim();
    const b = values[r.endField]?.trim();
    const endMeta = meta.fields.find((f) => f.name === r.endField);
    const startMeta = meta.fields.find((f) => f.name === r.startField);
    if (a && b && b < a && !errors[r.endField] && endMeta && startMeta) {
      errors[r.endField] = `${endMeta.label} must not be before ${startMeta.label.charAt(0).toLowerCase()}${startMeta.label.slice(1)}.`;
    }
  }
  return errors;
}

/** Server field errors look like records[2].passPercentage; map the ones for record `index` back to field names. */
export function serverErrorsFor(fieldErrors: Record<string, string> | undefined, index: number): FieldErrors {
  const out: FieldErrors = {};
  if (!fieldErrors) return out;
  const prefix = `records[${index}].`;
  for (const [k, v] of Object.entries(fieldErrors)) if (k.startsWith(prefix)) out[k.slice(prefix.length)] = v;
  return out;
}

/** The choices offered for a field now: all of them, or (for a dependent field) those of the value chosen above it. */
export function choicesFor(f: FieldMeta, values: FormValues): string[] {
  if (!f.dependsOn) return f.allowed ?? [];
  return f.allowedBy?.[values[f.dependsOn] ?? ""] ?? [];
}

/**
 * After `changed` has been set in `values`: a dependent choice that the new value no longer offers is cleared, and a
 * derived field (days) is worked out again from the section's date range.
 */
export function applyDependencies(meta: SectionMeta, values: FormValues, changed: string): FormValues {
  const out = { ...values };
  for (const f of meta.fields) {
    if (f.dependsOn === changed && out[f.name] && !choicesFor(f, out).includes(out[f.name])) out[f.name] = "";
    if (f.onlyWhenField === changed && !isAsked(f, out)) out[f.name] = "";
    if (f.derived && meta.dateRanges[0]) out[f.name] = inclusiveDays(out[meta.dateRanges[0].startField] ?? "", out[meta.dateRanges[0].endField] ?? "");
  }
  return out;
}

/** Inclusive number of days from `start` to `end` (both YYYY-MM-DD), or "" while either is missing or they are reversed. */
export function inclusiveDays(start: string, end: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(start) || !/^\d{4}-\d{2}-\d{2}$/.test(end)) return "";
  const a = Date.parse(`${start}T00:00:00Z`);
  const b = Date.parse(`${end}T00:00:00Z`);
  if (Number.isNaN(a) || Number.isNaN(b) || b < a) return "";
  return String(Math.round((b - a) / 86_400_000) + 1);
}
