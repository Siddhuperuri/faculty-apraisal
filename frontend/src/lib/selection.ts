/**
 * Choosing rows in a list, the way a mail inbox does: a checkbox on each row, one in the header for everything shown, and a
 * bar of actions that exists only while something is chosen. These are the rules, apart from the screen, so they can be
 * tested: the chosen ids are kept as a set that outlives the filters, so a search or a sort never drops a choice.
 */
export type Selection = ReadonlySet<number>;

export const NOTHING: Selection = new Set<number>();

/** The header checkbox: nothing, some, or all of what is shown is chosen. */
export type CheckState = "none" | "some" | "all";

export function toggle(sel: Selection, id: number): Selection {
  const next = new Set(sel);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** Chooses (or un-chooses) every one of `ids` and leaves the rest of the choice as it was. */
export function setMany(sel: Selection, ids: readonly number[], chosen: boolean): Selection {
  const next = new Set(sel);
  for (const id of ids) {
    if (chosen) next.add(id);
    else next.delete(id);
  }
  return next;
}

/** What the header checkbox shows for the rows now on screen. An empty list is "none". */
export function checkState(sel: Selection, shown: readonly number[]): CheckState {
  if (shown.length === 0) return "none";
  let chosen = 0;
  for (const id of shown) if (sel.has(id)) chosen++;
  return chosen === 0 ? "none" : chosen === shown.length ? "all" : "some";
}

/** Clicking the header: a full set is cleared, anything less is completed. Only the rows shown are touched. */
export function toggleShown(sel: Selection, shown: readonly number[]): Selection {
  return setMany(sel, shown, checkState(sel, shown) !== "all");
}

/** How many of the chosen rows a filter is hiding at the moment. */
export function hiddenCount(sel: Selection, shown: readonly number[]): number {
  const visible = new Set(shown);
  let hidden = 0;
  for (const id of sel) if (!visible.has(id)) hidden++;
  return hidden;
}

/** The choice without any id that is no longer in the list (after a reload); the same object when nothing changed. */
export function keepOnly(sel: Selection, valid: Iterable<number>): Selection {
  const ok = new Set(valid);
  let all = true;
  for (const id of sel) {
    if (!ok.has(id)) {
      all = false;
      break;
    }
  }
  if (all) return sel;
  return new Set([...sel].filter((id) => ok.has(id)));
}

/** The bar of bulk actions is there only while at least one row is chosen. */
export function barVisible(sel: Selection): boolean {
  return sel.size > 0;
}

/** "3 faculty selected", "1 user selected". */
export function selectedLabel(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural} selected`;
}
