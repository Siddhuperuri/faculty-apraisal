import { describe, expect, it } from "vitest";
import { byListOrder, inSequence, moveLabel, moveState } from "./sequence";
import type { ReferenceValue } from "./types";

const v = (id: number, code: string, sortOrder: number, extra: Partial<ReferenceValue> = {}): ReferenceValue => ({
  id,
  list: "SECTION",
  code,
  parent: "",
  label: code,
  reportLabel: null,
  sortOrder,
  active: true,
  locked: false,
  ...extra,
});

describe("moving a dropdown value", () => {
  const list = [v(1, "A", 10), v(2, "B", 20), v(3, "C", 30)];

  it("is shown in saved sequence whatever order the server sent", () => {
    expect(inSequence([list[2], list[0], list[1]]).map((x) => x.code)).toEqual(["A", "B", "C"]);
    expect(inSequence([v(9, "Z", 5), v(8, "Y", 5)]).map((x) => x.code)).toEqual(["Y", "Z"]);   // ties: by id
  });

  it("disables Up for the first and Down for the last", () => {
    expect(moveState(list, list[0])).toEqual({ canUp: false, canDown: true });
    expect(moveState(list, list[1])).toEqual({ canUp: true, canDown: true });
    expect(moveState(list, list[2])).toEqual({ canUp: true, canDown: false });
  });

  it("steps over switched-off values: the first and last are counted among the active ones", () => {
    const withOff = [v(1, "A", 10, { active: false }), v(2, "B", 20), v(3, "C", 30), v(4, "D", 40, { active: false })];
    expect(moveState(withOff, withOff[1])).toEqual({ canUp: false, canDown: true });
    expect(moveState(withOff, withOff[2])).toEqual({ canUp: true, canDown: false });
    expect(moveState(withOff, withOff[0])).toEqual({ canUp: false, canDown: false });
  });

  it("keeps each parent choice's values apart", () => {
    const branches = [
      v(1, "CSE", 10, { parent: "B_TECH" }),
      v(2, "CSE", 20, { parent: "M_TECH" }),
      v(3, "ECE", 30, { parent: "B_TECH" }),
      v(4, "ECE", 40, { parent: "M_TECH" }),
    ];
    expect(moveState(branches, branches[1])).toEqual({ canUp: false, canDown: true });   // first of M_TECH although second overall
    expect(moveState(branches, branches[2])).toEqual({ canUp: true, canDown: false });
  });

  it("labels the arrows with the value's wording", () => {
    expect(moveLabel(v(1, "THEORY", 10, { label: "Theory" }), "UP")).toBe("Move Theory up");
    expect(moveLabel(v(1, "THEORY", 10, { label: "Theory" }), "DOWN")).toBe("Move Theory down");
  });
});

describe("ordering filter options by the dropdown's sequence", () => {
  it("follows the offered order, then the values no longer offered", () => {
    expect(byListOrder(["LAB", "OLD", "THEORY"], ["THEORY", "LAB"])).toEqual(["THEORY", "LAB", "OLD"]);
    expect(byListOrder(["B", "A"], null)).toEqual(["A", "B"]);
    expect(byListOrder(["10", "9", "2"], ["2", "9"], true)).toEqual(["2", "9", "10"]);
  });
});
