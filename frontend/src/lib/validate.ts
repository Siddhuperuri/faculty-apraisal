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
  for (const f of meta.fields) rec[f.name] = convert(f, values[f.name] ?? "");
  return rec;
}

const num = (s: string) => (s.trim() === "" || Number.isNaN(Number(s)) ? null : Number(s));

function fieldError(f: FieldMeta, raw: string): string | null {
  const v = raw.trim();
  if (v === "") return f.required ? `${f.label} is required.` : null;
  switch (f.type) {
    case "TEXT":
      return f.maxLength != null && v.length > f.maxLength ? `${f.label} must be at most ${f.maxLength} characters.` : null;
    case "INT": {
      const n = num(v);
      if (n == null || !Number.isInteger(n)) return `${f.label} must be a whole number.`;
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return `${f.label} must be between ${f.min} and ${f.max}.`;
      return null;
    }
    case "DECIMAL": {
      const n = num(v);
      if (n == null) return `${f.label} must be a number.`;
      const dp = (v.split(".")[1] ?? "").replace(/0+$/, "").length;
      if (f.scale != null && dp > f.scale) return `${f.label} can have at most ${f.scale} decimal places.`;
      if ((f.min != null && n < f.min) || (f.max != null && n > f.max)) return `${f.label} must be between ${f.min} and ${f.max}.`;
      return null;
    }
    case "ENUM":
      return f.allowed?.includes(v) ? null : `${f.label} must be one of the listed options.`;
    case "DATE":
      return /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) ? null : `${f.label} must be a valid date.`;
    case "MONTH_YEAR":
      return /^\d{4}-(0[1-9]|1[0-2])$/.test(v) ? null : `${f.label} must be a month and year.`;
  }
}

/** Errors keyed by field name. Empty object means the values can be saved. */
export function validate(meta: SectionMeta, values: FormValues, hidden: string[] = []): FieldErrors {
  const errors: FieldErrors = {};
  for (const f of meta.fields) {
    if (hidden.includes(f.name)) continue;
    const e = fieldError(f, values[f.name] ?? "");
    if (e) errors[f.name] = e;
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

/**
 * Checks one typed self-score against the cadre maximum, with the server's wording. Empty is allowed (not entered).
 * @returns an error message, or null when the value can be saved
 */
export function validateScore(raw: string, max: number | null, criterionLabel: string): string | null {
  const v = raw.trim();
  if (v === "") return null;
  const label = `Self-score for ${criterionLabel}`;
  const n = Number(v);
  if (Number.isNaN(n)) return `${label} must be a number.`;
  const places = (v.split(".")[1] ?? "").replace(/0+$/, "").length;
  if (places > 2) return `${label} can have at most 2 decimal places.`;
  if (n < 0 && max === null) return `${label} cannot be below 0.`;
  if (max !== null && (n < 0 || n > max)) {
    return max === 0 ? `${label} is not applicable for your cadre (maximum 0).` : `${label} must be between 0 and ${max}.`;
  }
  return null;
}
