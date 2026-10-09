"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { LEVELS, type ReviewerRole } from "@/lib/hierarchy";
import type { YearRef } from "@/lib/types";

/** The tone of a number: what it asks of the reader. */
type Tone = "plain" | "action" | "warn" | "ok";

const TILE_TONE: Record<Tone, string> = {
  plain: "border-line",
  action: "border-navy ring-1 ring-navy/20",
  warn: "border-warn/50",
  ok: "border-ok/40",
};
const NUMBER_TONE: Record<Tone, string> = { plain: "text-ink", action: "text-navy", warn: "text-warn", ok: "text-ok" };

/** One figure in the ledger style: a large serif numeral over a small-caps label. A link when it leads somewhere. */
export function StatTile({
  label,
  value,
  hint,
  tone = "plain",
  href,
}: {
  label: string;
  value: number | string;
  hint?: ReactNode;
  tone?: Tone;
  href?: string;
}) {
  const body = (
    <>
      <p className="text-[11px] font-bold uppercase tracking-[0.16em] text-muted">{label}</p>
      <p className={`mt-1 font-display text-5xl font-medium leading-none tracking-tight tabular-nums ${NUMBER_TONE[tone]}`}>{value}</p>
      {hint && <p className="mt-2 text-xs text-muted">{hint}</p>}
    </>
  );
  const cls = `console-stat relative isolate block overflow-hidden rounded-xl border bg-surface p-5 shadow-[var(--shadow-paper)] ${TILE_TONE[tone]}`;
  return href ? (
    <Link href={href} className={`${cls} transition hover:-translate-y-px hover:shadow-[var(--shadow-lift)]`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  );
}

export interface Segment {
  key: string;
  label: string;
  value: number;
  /** A Tailwind background class. */
  className: string;
}

/**
 * Proportions as one bar with its legend. The numbers are always written out, so the colours only help; nothing
 * depends on seeing them.
 */
export function StageBar({ segments, total, caption, legend = true }: { segments: Segment[]; total?: number; caption?: string; legend?: boolean }) {
  const sum = total ?? segments.reduce((a, s) => a + s.value, 0);
  const summary = segments.map((s) => `${s.value} ${s.label.toLowerCase()}`).join(", ");
  return (
    <div>
      <div
        role="img"
        aria-label={`${caption ? caption + ": " : ""}${summary}`}
        className="flex h-3 w-full overflow-hidden rounded-full bg-line"
      >
        {sum > 0 &&
          segments
            .filter((s) => s.value > 0)
            .map((s) => <span key={s.key} className={s.className} style={{ width: `${(s.value / sum) * 100}%` }} />)}
      </div>
      {legend && (
        <ul className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted" aria-hidden>
          {segments.map((s) => (
            <li key={s.key} className="flex items-center gap-1.5">
              <span className={`inline-block h-2.5 w-2.5 rounded-[2px] ${s.className}`} />
              <span className="tabular-nums font-semibold text-ink">{s.value}</span> {s.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 2025-26 as 2025 - 26, the way the college writes the academic year in the console. */
const spaced = (name: string) => name.replace("-", " - ");

/** The place in the middle of the Console / Review queue tabs where the academic year is shown. */
export const YEAR_SLOT_ID = "console-year-slot";

/**
 * Shows, and lets the reader choose, the academic year a console is about. It appears in the middle of the tab row above the
 * console (see ReviewerTabs) wherever that row is on the page; with no tab row it stays where it is written. Hidden when
 * there is nothing to choose.
 */
export function YearPicker({ year, years, onChange }: { year: YearRef | null; years: YearRef[]; onChange: (id: number) => void }) {
  if (years.length === 0) return null;
  const picker =
    years.length === 1 ? (
      <p className="text-lg font-bold text-muted">Academic Year : <span className="text-ink">{spaced(years[0].name)}</span></p>
    ) : (
      <div className="flex items-center gap-2">
        <label htmlFor="console-year" className="text-lg font-bold">Academic Year :</label>
        <select
          id="console-year"
          value={year?.id ?? ""}
          onChange={(e) => onChange(Number(e.target.value))}
          className="rounded-sm border border-line-strong bg-surface px-3 py-1.5 text-sm"
        >
          {years.map((y) => (
            <option key={y.id} value={y.id}>{spaced(y.name)}</option>
          ))}
        </select>
      </div>
    );
  // The console only draws this after its data has arrived in the browser, so the tab row is already on the page.
  const slot = typeof document === "undefined" ? null : document.getElementById(YEAR_SLOT_ID);
  return slot ? createPortal(picker, slot) : picker;
}

/** The header every console shares: role eyebrow, serif title, a line of context, and room for controls. */
export function ConsoleHeader({ eyebrow, title, children, aside }: { eyebrow?: string; title: string; children?: ReactNode; aside?: ReactNode }) {
  return (
    <div className="rise flex flex-wrap items-end justify-between gap-4">
      <div>
        {eyebrow && <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">{eyebrow}</p>}
        <h1 className={`${eyebrow ? "mt-1 " : ""}font-display text-4xl font-medium tracking-tight text-navy sm:text-5xl`}>{title}</h1>
        {children && <div className="mt-1 text-sm text-muted">{children}</div>}
      </div>
      {aside}
    </div>
  );
}

/** Tabs for a reviewer's area (Head of the Department, Principal or Director Technical): the console and the full review queue. */
export function ReviewerTabs({ role }: { role: ReviewerRole }) {
  const pathname = usePathname();
  const tabs = [
    { href: LEVELS[role].base, label: "Console" },
    { href: "/review", label: "Review queue" },
  ];
  return (
    <nav aria-label="Sections" className="section-tabs border-b border-line-strong sm:grid sm:grid-cols-[1fr_auto_1fr] sm:items-end">
      <ul className="-mb-px flex flex-wrap gap-1">
        {tabs.map((t) => {
          const current = pathname === t.href;
          return (
            <li key={t.href}>
              <Link
                href={t.href}
                aria-current={current ? "page" : undefined}
                className={`inline-block rounded-t-md border-x border-t px-4 py-2 text-sm font-semibold transition-colors ${
                  current ? "border-line-strong border-b-surface bg-surface text-navy" : "border-transparent text-muted hover:text-ink"
                }`}
              >
                {t.label}
              </Link>
            </li>
          );
        })}
      </ul>
      <div id={YEAR_SLOT_ID} className="px-3 pb-2 pt-2 text-center sm:pt-0" />
      <span aria-hidden className="hidden sm:block" />
    </nav>
  );
}

/** Stage colours, shared so every console draws the same thing the same way. */
export const STAGE_COLOUR: Record<string, string> = {
  NOT_SUBMITTED: "bg-line-strong",
  NEEDS_HOD: "bg-fill",
  ONWARD: "bg-brand",
  APPROVED: "bg-ok",
};
