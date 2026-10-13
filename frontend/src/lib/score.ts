/** A rate as it is worded wherever per-entry marks are shown: "50m for each" (never "(50 each)"). */
export function perEntryLabel(marks: number): string {
  return `${Number.isInteger(marks) ? marks : Number(marks.toFixed(2))}m for each`;
}

/** The line a faculty member sees for a criterion: its number and title, then what it is worth and what they have earned. */
export function criterionSummary(reference: string, label: string, score: number, maxMarks: number | null): string {
  const earned = Number.isInteger(score) ? String(score) : String(Number(score.toFixed(2)));
  return maxMarks === null ? `${reference} · ${label} — ${earned} (no maximum)` : `${reference} · ${label} — ${earned} of ${maxMarks}`;
}
