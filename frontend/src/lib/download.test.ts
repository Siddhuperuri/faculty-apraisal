import { describe, expect, it } from "vitest";
import { fileNameFrom } from "./api";

describe("the name of a downloaded file", () => {
  it("reads the extended form first, then the plain one", () => {
    expect(fileNameFrom("attachment; filename*=UTF-8''faculty%20appraisals-cse-2025-26.xlsx")).toBe("faculty appraisals-cse-2025-26.xlsx");
    expect(fileNameFrom('attachment; filename="users-2026-10-12.csv"')).toBe("users-2026-10-12.csv");
    expect(fileNameFrom("attachment; filename=users.csv")).toBe("users.csv");
  });

  it("falls back to a safe name, and never takes a path from the server", () => {
    expect(fileNameFrom(null, "export.xlsx")).toBe("export.xlsx");
    expect(fileNameFrom('attachment; filename="../../etc/passwd"', "export.csv")).toBe("passwd");
  });
});
