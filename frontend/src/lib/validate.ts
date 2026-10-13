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

const DOI = /^10\.\d{4,9}\/\S+$/;
const ISSN = /^\d{4}-\d{3}[\dXx]$/;
const LABEL_ONLY = /^(?:doi|issn|e-?issn|p-?issn|print|online)[:.]?$/i;
const DOI_PREFIX = /^(?:https?:\/\/(?:dx\.)?doi\.org\/|doi\.org\/|doi:)/i;
const ISSN_PREFIX = /^(?:e-?issn|p-?issn|issn):/i;

/** NNNN-NNN and a check character: the digits weighted 8 down to 2 and the check must make the sum divide by 11. */
function validIssn(issn: string): boolean {
  const d = issn.replace("-", "").toUpperCase();
  let sum = 0;
  for (let i = 0; i < 7; i++) sum += (8 - i) * Number(d[i]);
  const check = (11 - (sum % 11)) % 11;
  return d[7] === (check === 10 ? "X" : String(check));
}

/**
 * At least one value in the text is a valid DOI (10.NNNN/anything, or written doi:10... or as a doi.org address) or a
 * valid ISSN (NNNN-NNNN with its check character), as the server counts it. Values are separated by spaces, commas or semicolons.
 */
export function hasValidDoiOrIssn(text: string): boolean {
  for (const token of text.trim().split(/[\s,;]+/)) {
    if (token === "" || LABEL_ONLY.test(token)) continue;
    if (DOI.test(token.replace(DOI_PREFIX, ""))) return true;
    const issn = token.replace(ISSN_PREFIX, "");
    if (ISSN.test(issn) && validIssn(issn)) return true;
  }
  return false;
}

/** An ISBN-10 or ISBN-13, with or without hyphens and spaces, whose length, characters and check digit are right. */
export function validIsbn(raw: string): boolean {
  const d = raw.replace(/[-\s]/g, "").toUpperCase();
  if (/^\d{9}[\dX]$/.test(d)) {
    let sum = 0;
    for (let i = 0; i < 10; i++) sum += (10 - i) * (d[i] === "X" ? 10 : Number(d[i]));
    return sum % 11 === 0;
  }
  if (/^97[89]\d{10}$/.test(d)) {
    let sum = 0;
    for (let i = 0; i < 13; i++) sum += Number(d[i]) * (i % 2 === 0 ? 1 : 3);
    return sum % 10 === 0;
  }
  return false;
}

const num = (s: string) => (s.trim() === "" || Number.isNaN(Number(s)) ? null : Number(s));

/** The number of words in a text: runs of characters between spaces, tabs and line breaks (as the server counts them). */
export function countWords(text: string): number {
  const found = text.trim().match(/\S+/g);
  return found ? found.length : 0;
}

/**
 * What is wrong with one value, in the server's wording; null when it is fine. The same check runs as a person types or
 * leaves a field and again on save; the server remains the authority.
 */
export function checkField(f: FieldMeta, raw: string, allowed: string[] | null = f.allowed): string | null {
  return fieldError(f, raw, allowed);
}

function fieldError(f: FieldMeta, raw: string, allowed: string[] | null = f.allowed): string | null {
  const v = raw.trim();
  if (v === "") return f.required ? `${f.label} is required.` : null;
  switch (f.type) {
    case "TEXT": {
      if (f.maxWords != null) {
        const words = countWords(v);
        if (words > f.maxWords) return `${f.label} must be at most ${f.maxWords.toLocaleString("en-US")} words (it has ${words.toLocaleString("en-US")}).`;
      }
      if (f.maxLength != null && v.length > f.maxLength) return `${f.label} must be at most ${f.maxLength} characters.`;
      if (f.format === "DOI_ISSN" && !hasValidDoiOrIssn(v)) {
        return `${f.label} must include a valid DOI (such as 10.1000/xyz123) or ISSN (such as 1234-5678).`;
      }
      if (f.format === "ISBN" && !validIsbn(v)) return `${f.label} must be a valid ISBN-10 or ISBN-13 (for example 978-0-13-468599-1).`;
      return null;
    }
    case "INT": {
      const n = num(v);
      if (n == null || !Number.isInteger(n)) return `${f.label} must be a whole number.`;
      if (f.allowed?.length && !f.allowed.includes(String(n))) return `${f.label} must be one of: ${f.allowed.join(", ")}.`;
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return `${f.label} must be between ${f.min} and ${f.max}.`;
      return null;
    }
    case "DECIMAL": {
      const n = num(v);
      if (f.format === "CURRENCY") {
        const places = (v.split(".")[1] ?? "").replace(/0+$/, "").length;
        if (n == null || n < 0 || places > 2 || /[a-z,]/i.test(v)) {
          return `${f.label} must be a valid amount: 0 or more, with at most 2 decimal places.`;
        }
        return f.max != null && n > f.max ? `${f.label} must be at most ${Math.trunc(f.max)}.` : null;
      }
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

/**
 * The problem a value has by the field's own limits, or null: what the person sees as they type or on leaving the field.
 * An empty value is a problem only when the field is required; a date, month or year that must fall in the academic year is
 * checked when the year is known.
 */
export function liveFieldError(
  meta: FieldMeta,
  value: string,
  required: boolean,
  range?: { start: string; end: string; name?: string },
): string | null {
  if (value.trim() === "") return required ? `${meta.label} is required.` : null;
  const found = fieldError(meta, value, meta.allowed);
  if (found) return found;
  if (meta.inAcademicYear && range?.name) return outsideYear(meta, value.trim(), { name: range.name, start: range.start, end: range.end });
  return null;
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

/** "Role is Other", "Status is Sanctioned": the condition under which a conditional field is asked for, as the server words it. */
export function requiredWhen(meta: SectionMeta, f: FieldMeta): string {
  const parent = meta.fields.find((x) => x.name === f.onlyWhenField);
  const value = f.onlyWhenEquals ?? "";
  const wording = parent?.labels?.[value] ?? (value === "OTHER" ? "Other" : value);
  return `${parent?.label ?? "the choice above"} is ${wording}`;
}

/** Errors keyed by field name. Empty object means the values can be saved. */
export function validate(meta: SectionMeta, values: FormValues, hidden: string[] = [], year?: YearBounds): FieldErrors {
  const errors: FieldErrors = {};
  for (const f of meta.fields) {
    if (hidden.includes(f.name) || f.derived || f.effective || !isAsked(f, values)) continue;   // worked out, not typed
    if (f.onlyWhenField && (values[f.name] ?? "").trim() === "") {
      errors[f.name] = `${f.label} is required when ${requiredWhen(meta, f)}.`;
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
