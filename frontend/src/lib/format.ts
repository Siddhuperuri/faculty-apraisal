import type { FieldMeta, Rec } from "./types";
import { enumLabel } from "./labels";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** 2026-10-03 -> 03-10-2026 (the form's DD-MM-YYYY). */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "";
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}-${m[2]}-${m[1]}` : iso;
}

/** 2026-05 -> May 2026. */
export function formatMonthYear(v: string | null | undefined): string {
  if (!v) return "";
  const m = /^(\d{4})-(\d{2})$/.exec(v);
  return m ? `${MONTHS[Number(m[2]) - 1]} ${m[1]}` : v;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const text = d.toLocaleString("en-IN", { day: "2-digit", month: "2-digit", year: "numeric", hour: "numeric", minute: "2-digit", hour12: true });
  return text.replace(/\//g, "-"); // DD-MM-YYYY, like the form
}

const rupees = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 });

export function formatRupees(n: number | null | undefined): string {
  return n == null ? "" : rupees.format(n);
}

/** 92.50 -> "92.5", 4 -> "4". */
export function formatNumber(n: number | null | undefined): string {
  if (n == null) return "";
  return String(Number(n));
}

/** A saved value as shown in a table cell. */
export function formatCell(field: FieldMeta, value: unknown): string {
  if (value == null || value === "") return "";
  switch (field.type) {
    case "DATE":
      return formatDate(String(value));
    case "MONTH_YEAR":
      return formatMonthYear(String(value));
    case "ENUM":
      return enumLabel(String(value));
    case "DECIMAL":
      return field.name === "amount" ? formatRupees(Number(value)) : formatNumber(Number(value));
    case "INT":
      return String(value);
    default:
      return String(value);
  }
}

export function duration(r: Rec, from = "startDate", to = "endDate"): string {
  const a = formatDate(r[from] as string | undefined);
  const b = formatDate(r[to] as string | undefined);
  return a && b ? `${a} to ${b}` : a || b;
}

/** "3 minutes ago" style text for the save indicator. */
export function ago(from: Date, now: Date): string {
  const s = Math.max(0, Math.round((now.getTime() - from.getTime()) / 1000));
  if (s < 10) return "just now";
  if (s < 60) return `${s} seconds ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} minute${m === 1 ? "" : "s"} ago`;
  const h = Math.round(m / 60);
  return `${h} hour${h === 1 ? "" : "s"} ago`;
}

/** 1536 -> "1.5 KB". */
export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1).replace(/\.0$/, "")} MB`;
}
