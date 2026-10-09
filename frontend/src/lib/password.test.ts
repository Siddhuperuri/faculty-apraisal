import { describe, expect, it } from "vitest";
import { byteLength, passwordChecks, passwordProblem } from "./password";

describe("password hints", () => {
  it("accepts a reasonable password", () => {
    expect(passwordProblem("maple-river-42", "asha@svec.edu")).toBeNull();
    expect(passwordProblem("a sentence with 1 number")).toBeNull();
  });

  it("names the first rule that is not met", () => {
    expect(passwordProblem("short1")).toMatch(/at least 10/i);
    expect(passwordProblem("onlylettersnodigits")).toMatch(/letter and a number/i);
    expect(passwordProblem("12345678901234")).toMatch(/letter and a number/i);
    expect(passwordProblem(" leading-space-1")).toMatch(/space/i);
    expect(passwordProblem("trailing-space-1 ")).toMatch(/space/i);
  });

  it("counts bytes, not characters, against the 72-byte limit", () => {
    expect(byteLength("é")).toBe(2);
    expect(passwordProblem("a1" + "x".repeat(70))).toBeNull(); // exactly 72 bytes
    expect(passwordProblem("a1" + "x".repeat(71))).toMatch(/72 bytes/);
    expect(passwordProblem("é1" + "é".repeat(36))).toMatch(/72 bytes/); // 38 characters, 75 bytes
  });

  it("forbids the e-mail name only when it is long enough to matter", () => {
    expect(passwordProblem("ashavardhan-2025", "ashavardhan@svec.edu")).toMatch(/e-mail/i);
    expect(passwordProblem("blue-moon-2025", "bo@svec.edu")).toBeNull();
    expect(passwordChecks("blue-moon-2025", "bo@svec.edu")).toHaveLength(4);
    expect(passwordChecks("blue-moon-2025", "ashavardhan@svec.edu")).toHaveLength(5);
  });

  it("does not blame an empty password for spaces it does not have", () => {
    const failed = passwordChecks("").filter((c) => !c.ok).map((c) => c.rule);
    expect(failed).toEqual(["At least 10 characters", "A letter and a number"]);
  });
});
