import type { RosterRow } from "./types";

/** Where a faculty member's appraisal stands, from the HoD's side. "" means everyone. */
export type RosterView = "" | "NEEDS_ACTION" | "QUERY" | "ONWARD" | "APPROVED" | "NOT_SUBMITTED";
export type RosterSort = "" | "NAME" | "OLDEST" | "NEWEST";

export const VIEW_ORDER: Exclude<RosterView, "">[] = ["NEEDS_ACTION", "QUERY", "ONWARD", "APPROVED", "NOT_SUBMITTED"];

export const VIEW_LABEL: Record<Exclude<RosterView, "">, string> = {
  NEEDS_ACTION: "Needs your action",
  QUERY: "Query raised (awaiting the faculty member)",
  ONWARD: "With the Principal / Director",
  APPROVED: "Approved",
  NOT_SUBMITTED: "Not yet submitted",
};

export const SORT_LABEL: Record<Exclude<RosterSort, "">, string> = {
  NAME: "Name, A to Z",
  OLDEST: "Submitted earliest first",
  NEWEST: "Submitted most recently first",
};

/** An appraisal with a query raised is with the HoD but waiting for the faculty member, so it is its own group. */
export function viewOf(r: RosterRow): Exclude<RosterView, ""> {
  if (r.stage === "NEEDS_HOD") return r.queryRaised ? "QUERY" : "NEEDS_ACTION";
  return r.stage;
}

export interface RosterFilter {
  view: RosterView;
  department: string;
  cadre: string;
  query: string;
}

export function filterRoster(rows: RosterRow[], f: RosterFilter, sort: RosterSort): RosterRow[] {
  const q = f.query.trim().toLowerCase();
  const out = rows.filter(
    (r) =>
      (!f.view || viewOf(r) === f.view) &&
      (!f.department || r.department === f.department) &&
      (!f.cadre || r.cadre === f.cadre) &&
      (!q || `${r.name} ${r.employeeId} ${r.department} ${r.cadre}`.toLowerCase().includes(q)),
  );
  if (sort === "NAME") return [...out].sort((a, b) => a.name.localeCompare(b.name));
  if (sort === "OLDEST" || sort === "NEWEST") {
    const time = (r: RosterRow) => (r.submittedAt ? Date.parse(r.submittedAt) : null);
    const dir = sort === "OLDEST" ? 1 : -1;
    // Those who have not submitted have no date, so they go last either way.
    return [...out].sort((a, b) => {
      const x = time(a);
      const y = time(b);
      if (x === null && y === null) return 0;
      if (x === null) return 1;
      if (y === null) return -1;
      return (x - y) * dir;
    });
  }
  return out;
}
