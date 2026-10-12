import { describe, expect, it } from "vitest";
import { NOTHING, barVisible, checkState, hiddenCount, keepOnly, selectedLabel, setMany, toggle, toggleShown } from "./selection";

describe("choosing rows", () => {
  it("toggles one row without touching the others and without changing the old choice", () => {
    const one = toggle(NOTHING, 5);
    expect([...one]).toEqual([5]);
    expect(NOTHING.size).toBe(0);
    expect([...toggle(one, 7)].sort()).toEqual([5, 7]);
    expect([...toggle(one, 5)]).toEqual([]);
  });

  it("the header checkbox is none, some or all of the rows shown", () => {
    const shown = [1, 2, 3];
    expect(checkState(NOTHING, shown)).toBe("none");
    expect(checkState(new Set([2]), shown)).toBe("some");
    expect(checkState(new Set([1, 2, 3, 9]), shown)).toBe("all");
    expect(checkState(new Set([9]), shown)).toBe("none");   // chosen, but not shown
    expect(checkState(new Set([1]), [])).toBe("none");
  });

  it("clicking the header completes a partial choice and then clears it, touching only the rows shown", () => {
    const hidden = new Set([99]);                            // chosen earlier, filtered out of view now
    const shown = [1, 2, 3];
    const all = toggleShown(hidden, shown);
    expect([...all].sort((a, b) => a - b)).toEqual([1, 2, 3, 99]);
    const partial = toggleShown(new Set([1, 99]), shown);
    expect([...partial].sort((a, b) => a - b)).toEqual([1, 2, 3, 99]);
    const cleared = toggleShown(all, shown);
    expect([...cleared]).toEqual([99]);                      // the hidden choice survives
  });

  it("keeps the choice while filtering and says how many chosen rows are out of sight", () => {
    const chosen = setMany(NOTHING, [1, 2, 3, 4], true);
    expect(hiddenCount(chosen, [1, 2, 3, 4])).toBe(0);
    expect(hiddenCount(chosen, [2, 3])).toBe(2);
    expect(hiddenCount(chosen, [])).toBe(4);
    expect(chosen.size).toBe(4);                             // nothing was dropped by looking elsewhere
  });

  it("drops only ids that are no longer in the list", () => {
    const chosen = new Set([1, 2, 3]);
    expect(keepOnly(chosen, [1, 2, 3, 4])).toBe(chosen);     // unchanged: the same object, so nothing re-renders
    expect([...keepOnly(chosen, [2, 3])]).toEqual([2, 3]);
  });

  it("shows the bulk bar only while something is chosen", () => {
    expect(barVisible(NOTHING)).toBe(false);
    expect(barVisible(new Set([1]))).toBe(true);
    expect(barVisible(toggle(new Set([1]), 1))).toBe(false);
  });

  it("words the count", () => {
    expect(selectedLabel(3, "faculty", "faculty")).toBe("3 faculty selected");
    expect(selectedLabel(1, "user", "users")).toBe("1 user selected");
    expect(selectedLabel(2, "user", "users")).toBe("2 users selected");
  });
});
