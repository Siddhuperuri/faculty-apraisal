import { describe, expect, it } from "vitest";
import { ago, formatBytes, formatDate, formatMonthYear, formatNumber, formatRupees } from "./format";
import { enumLabel } from "./labels";

describe("format", () => {
  it("shows dates as DD-MM-YYYY like the form", () => {
    expect(formatDate("2026-10-03")).toBe("03-10-2026");
    expect(formatDate(null)).toBe("");
  });

  it("shows month and year in words", () => {
    expect(formatMonthYear("2026-05")).toBe("May 2026");
    expect(formatMonthYear("2026-12")).toBe("Dec 2026");
  });

  it("trims needless decimals but keeps real ones", () => {
    expect(formatNumber(92.5)).toBe("92.5");
    expect(formatNumber(4)).toBe("4");
    expect(formatNumber(null)).toBe("");
  });

  it("groups rupees the Indian way", () => {
    expect(formatRupees(250000.5)).toContain("2,50,000.5");
  });

  it("shows file sizes plainly", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(204800)).toBe("200 KB");
    expect(formatBytes(10 * 1024 * 1024)).toBe("10 MB");
    expect(formatBytes(2.5 * 1024 * 1024)).toBe("2.5 MB");
  });

  it("describes elapsed time for the save indicator", () => {
    const t = new Date("2026-10-03T10:00:00Z");
    expect(ago(t, new Date("2026-10-03T10:00:03Z"))).toBe("just now");
    expect(ago(t, new Date("2026-10-03T10:00:40Z"))).toBe("40 seconds ago");
    expect(ago(t, new Date("2026-10-03T10:02:00Z"))).toBe("2 minutes ago");
    expect(ago(t, new Date("2026-10-03T11:00:00Z"))).toBe("1 hour ago");
  });
});

describe("labels", () => {
  it("uses the official wording for stored values", () => {
    expect(enumLabel("CO_PI")).toBe("Co-PI");
    expect(enumLabel("INTL")).toBe("International");
    expect(enumLabel("JOURNAL_REVIEWER")).toBe("Reviewer for Journals");
    expect(enumLabel("VIVA_COMPLETED")).toBe("Viva-voce completed");
  });

  it("falls back to readable text for unknown values", () => {
    expect(enumLabel("SOME_NEW_VALUE")).toBe("Some new value");
  });
});
