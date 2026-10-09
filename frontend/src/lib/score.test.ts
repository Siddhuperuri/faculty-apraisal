import { describe, expect, it } from "vitest";
import { validateScore } from "./validate";

const L = "Teaching & Learning";

describe("validateScore (same wording as the server)", () => {
  it("allows an empty score: not entered yet", () => {
    expect(validateScore("", 30, L)).toBeNull();
    expect(validateScore("   ", 30, L)).toBeNull();
  });

  it("accepts 0, the maximum and values in between, with up to two decimals", () => {
    for (const v of ["0", "30", "25", "12.5", "12.25", "7.50"]) expect(validateScore(v, 30, L), v).toBeNull();
  });

  it("rejects values above the maximum or below zero, naming the criterion and the limit", () => {
    expect(validateScore("31", 30, L)).toBe("Self-score for Teaching & Learning must be between 0 and 30.");
    expect(validateScore("-1", 30, L)).toBe("Self-score for Teaching & Learning must be between 0 and 30.");
  });

  it("rejects non-numbers and too many decimal places", () => {
    expect(validateScore("abc", 30, L)).toBe("Self-score for Teaching & Learning must be a number.");
    expect(validateScore("2.555", 30, L)).toBe("Self-score for Teaching & Learning can have at most 2 decimal places.");
  });

  it("has no upper limit for a criterion marked per entry, only a lower one", () => {
    expect(validateScore("500", null, "Outreach")).toBeNull();
    expect(validateScore("-1", null, "Outreach")).toBe("Self-score for Outreach cannot be below 0.");
  });

  it("explains a criterion whose maximum is 0 for the cadre", () => {
    expect(validateScore("1", 0, "Funded Projects / Consultancy")).toBe(
      "Self-score for Funded Projects / Consultancy is not applicable for your cadre (maximum 0).",
    );
    expect(validateScore("0", 0, "Funded Projects / Consultancy")).toBeNull();
  });
});
