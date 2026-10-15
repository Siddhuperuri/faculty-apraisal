import type { UserGroup } from "./types";

/**
 * The user pie chart on the analytics page, apart from the screen: the accounts arrive already counted by role, state,
 * department and designation, and the page narrows them with filters and groups them by one of those four. Only counts are
 * ever handled here.
 */
export type Dimension = "role" | "status" | "department" | "designation";

export interface UserFilters {
  role: string;
  status: string;
  department: string;
  designation: string;
}

export const NO_FILTERS: UserFilters = { role: "", status: "", department: "", designation: "" };

/** What a group has no value for, as the chart names it. */
const NONE: Record<Dimension, string> = {
  role: "Unknown",
  status: "Unknown",
  department: "No department",
  designation: "No designation",
};

export function valueOf(g: UserGroup, d: Dimension): string {
  const v = d === "role" ? g.role : d === "status" ? g.status : d === "department" ? g.department : g.designation;
  return v ?? "";
}

export function filterGroups(groups: readonly UserGroup[], f: UserFilters): UserGroup[] {
  return groups.filter(
    (g) =>
      (!f.role || g.role === f.role) &&
      (!f.status || g.status === f.status) &&
      (!f.department || valueOf(g, "department") === f.department) &&
      (!f.designation || valueOf(g, "designation") === f.designation),
  );
}

export interface Tally {
  key: string;
  value: number;
}

/** The count for each value of `by`, largest first (ties by name), with the accounts that have no value kept together at the end. */
export function tally(groups: readonly UserGroup[], by: Dimension): Tally[] {
  const counts = new Map<string, number>();
  for (const g of groups) {
    const key = valueOf(g, by);
    counts.set(key, (counts.get(key) ?? 0) + g.count);
  }
  const rows = [...counts.entries()].map(([key, value]) => ({ key, value })).filter((r) => r.value > 0);
  const named = rows.filter((r) => r.key !== "").sort((a, b) => b.value - a.value || a.key.localeCompare(b.key));
  const none = rows.filter((r) => r.key === "").map((r) => ({ key: "", value: r.value }));
  return [...named, ...none];
}

export function noneLabel(by: Dimension): string {
  return NONE[by];
}

/** The values a filter can take, from the groups there are, in a stable order. */
export function optionsFor(groups: readonly UserGroup[], d: Dimension): string[] {
  return [...new Set(groups.map((g) => valueOf(g, d)).filter((v) => v !== ""))].sort((a, b) => a.localeCompare(b));
}

/** 0 to 100 to one decimal place; 0 when there is nothing to divide by. */
export function percent(part: number, whole: number): number {
  return whole <= 0 ? 0 : Math.round((1000 * part) / whole) / 10;
}
