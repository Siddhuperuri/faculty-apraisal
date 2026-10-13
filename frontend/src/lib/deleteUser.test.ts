import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { confirmationMatches, deletionResultMessage, deletionWarning } from "./deleteUser";
import type { DeletionPreview } from "./types";

const preview = (over: Partial<DeletionPreview> = {}): DeletionPreview => ({
  id: 7,
  email: "ramesh.cse@vasavi.edu",
  name: "Dr. Ramesh Varma",
  role: "FACULTY",
  status: "ACTIVE",
  canDelete: true,
  blockedReason: null,
  appraisalsRemoved: 0,
  appraisalsKept: 0,
  keepsAnonymousRecord: false,
  departmentsUnassigned: [],
  departmentsLeftWithoutHead: [],
  ...over,
});

describe("confirming an irreversible deletion", () => {
  it("needs the account's own e-mail address, typed", () => {
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "")).toBe(false);
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "DELETE")).toBe(false);
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "ramesh.cse@vasavi")).toBe(false);
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "someone.else@vasavi.edu")).toBe(false);
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "ramesh.cse@vasavi.edu")).toBe(true);
  });

  it("does not mind capitals or spaces around it", () => {
    expect(confirmationMatches("ramesh.cse@vasavi.edu", "  Ramesh.CSE@Vasavi.edu ")).toBe(true);
  });

  it("is never satisfied by nothing, even when there is no address to match", () => {
    expect(confirmationMatches("", "")).toBe(false);
    expect(confirmationMatches("  ", "  ")).toBe(false);
  });
});

describe("the warning shown before deleting", () => {
  it("names the person, their e-mail address and role", () => {
    const w = deletionWarning(preview(), "Faculty");
    expect(w.headline).toBe("Delete Dr. Ramesh Varma (ramesh.cse@vasavi.edu), Faculty, permanently?");
  });

  it("falls back to the address when there is no name", () => {
    expect(deletionWarning(preview({ name: null }), "Principal").headline).toBe("Delete ramesh.cse@vasavi.edu, Principal, permanently?");
  });

  it("says what is removed, and that it cannot be undone", () => {
    const w = deletionWarning(preview({ appraisalsRemoved: 2 }), "Faculty");
    expect(w.removes.join("\n")).toContain("sign-in");
    expect(w.removes.join("\n")).toContain("faculty record");
    expect(w.removes.join("\n")).toContain("2 appraisals never sent for review");
    expect(w.consequences).toContain("This cannot be undone.");
  });

  it("says what is kept without the person when the review depends on it", () => {
    const w = deletionWarning(preview({ appraisalsKept: 1, keepsAnonymousRecord: true }), "Faculty");
    expect(w.keeps.join("\n")).toContain("1 appraisal that went through review");
    expect(w.keeps.join("\n")).toContain("Deleted user");
    expect(w.keeps.join("\n")).toContain("neither the e-mail address nor the name");
  });

  it("names the departments a Head of the Department leaves without anyone", () => {
    const w = deletionWarning(
      preview({ role: "HOD", departmentsUnassigned: ["CSE (Computer Science)", "ME (Mechanical)"], departmentsLeftWithoutHead: ["ME (Mechanical)"] }),
      "Head of the Department",
    );
    expect(w.removes.join("\n")).toContain("Head of the Department of CSE (Computer Science), ME (Mechanical)");
    expect(w.consequences[0]).toContain("ME (Mechanical) will have no Head of the Department");
    expect(w.consequences.at(-1)).toBe("This cannot be undone.");
    expect(w.removes.join("\n")).not.toContain("faculty record");
  });
});

describe("the line shown afterwards", () => {
  it("counts what went and what stayed", () => {
    expect(deletionResultMessage("a@b.edu", { id: 1, outcome: "REMOVED", appraisalsRemoved: 1, appraisalsKept: 0, departmentsUnassigned: 0 })).toBe(
      "a@b.edu was deleted permanently. 1 appraisal was removed with it.",
    );
    expect(deletionResultMessage("a@b.edu", { id: 1, outcome: "ANONYMIZED", appraisalsRemoved: 0, appraisalsKept: 3, departmentsUnassigned: 0 })).toBe(
      "a@b.edu was deleted permanently. 3 appraisals were kept without the person.",
    );
  });
});

describe("the screens", () => {
  const source = (file: string) => readFileSync(path.resolve(__dirname, "..", file), "utf8");

  it("the delete dialog asks for the typed address and keeps the button disabled until it matches", () => {
    const dialog = source("components/admin/DeleteUserDialog.tsx");
    expect(dialog).toContain("confirmationMatches(preview.email, typed)");
    expect(dialog).toContain("disabled={!ready}");
    expect(dialog).toContain("/delete-permanently");
    expect(dialog).toContain("deletion-preview");
  });

  it("the users page offers it for every account and disables it for the signed-in one", () => {
    const page = source("app/(app)/admin/users/page.tsx");
    expect(page).toContain("<DeleteUserDialog");
    expect((page.match(/Delete permanently/g) ?? []).length).toBeGreaterThanOrEqual(4);
    expect(page).toContain("You cannot delete the account you are signed in with");
  });

  it("no form prints a range or limit under a field", () => {
    const input = source("components/ui/FieldInput.tsx");
    expect(input).not.toContain("rangeHint");
    expect(input).not.toMatch(/Between \$\{/);
    const fields = source("components/appraisal/RecordFields.tsx");
    expect(fields).not.toContain("rangeHint");
    expect(fields).not.toContain("Within the academic year");
    expect(fields).not.toContain("the academic year being appraised");
    expect(source("lib/formStructure.ts")).not.toContain("fieldHints");
  });

  it("the faculty member has no review history panel, and the course requirement box is gone", () => {
    const faculty = source("app/(app)/faculty/page.tsx");
    expect(faculty).not.toContain("HistoryTimeline");
    expect(faculty).not.toContain("What has happened");
    const appraisal = source("app/(app)/appraisals/[id]/page.tsx");
    expect(appraisal).toMatch(/\{!isFaculty && \(\s*<Card[^>]*>\s*<RuledHeading>Review history/);
    expect(() => source("components/appraisal/CourseLoadHelp.tsx")).toThrow();
    expect(source("app/(app)/appraisals/[id]/[page]/page.tsx")).not.toContain("CourseLoadHelp");
  });
});
