import type { ReferenceValue } from "./types";

/** Values in the order the administration page shows them: by the choice they belong to, then by saved sequence. */
export function inSequence(values: ReferenceValue[]): ReferenceValue[] {
  return [...values].sort((a, b) => a.parent.localeCompare(b.parent) || a.sortOrder - b.sortOrder || a.id - b.id);
}

/**
 * Whether a value may move one place earlier or later. Only active values move, and only among the active values of
 * the same list and the same parent choice (a branch moves among the branches of its program): Up is off for the first
 * of them, Down for the last, and both for a switched-off value.
 */
export function moveState(values: ReferenceValue[], value: ReferenceValue): { canUp: boolean; canDown: boolean } {
  if (!value.active) return { canUp: false, canDown: false };
  const group = inSequence(values).filter((v) => v.active && v.parent === value.parent);
  const at = group.findIndex((v) => v.id === value.id);
  return { canUp: at > 0, canDown: at >= 0 && at < group.length - 1 };
}

/** What the screen reader and the tooltip say for an arrow. */
export function moveLabel(value: ReferenceValue, direction: "UP" | "DOWN"): string {
  return `Move ${value.label} ${direction === "UP" ? "up" : "down"}`;
}

/**
 * The given values in the order of the dropdown's saved sequence (`order` is what the form offers, in that order).
 * A value the list no longer offers (switched off after a record used it) goes after the others, in natural order.
 */
export function byListOrder(values: string[], order: string[] | null | undefined, numeric = false): string[] {
  const place = new Map((order ?? []).map((code, i) => [code, i] as const));
  const natural = (a: string, b: string) => (numeric ? Number(a) - Number(b) || a.localeCompare(b) : a.localeCompare(b));
  return [...values].sort((a, b) => {
    const pa = place.get(a);
    const pb = place.get(b);
    if (pa !== undefined && pb !== undefined) return pa - pb;
    if (pa !== undefined) return -1;
    if (pb !== undefined) return 1;
    return natural(a, b);
  });
}
