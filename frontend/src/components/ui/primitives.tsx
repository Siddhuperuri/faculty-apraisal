import type { ButtonHTMLAttributes, ReactNode } from "react";
import { PAGES } from "@/lib/formStructure";
import { STATUS_INFO } from "@/lib/labels";
import type { Status } from "@/lib/types";

type Variant = "primary" | "secondary" | "ghost" | "danger";

const VARIANT: Record<Variant, string> = {
  primary: "bg-fill text-white shadow-[0_3px_8px_rgb(18_35_63/0.18)] hover:-translate-y-0.5 hover:bg-fill-hover hover:shadow-[0_7px_16px_rgb(18_35_63/0.2)] active:translate-y-0 active:shadow-none",
  secondary: "border border-line-strong bg-surface text-ink shadow-[0_2px_4px_rgb(20_30_54/0.05)] hover:-translate-y-0.5 hover:border-navy/30 hover:bg-brand-soft active:translate-y-0 active:shadow-none",
  ghost: "text-brand hover:bg-brand-soft",
  danger: "bg-bad text-surface shadow-[0_3px_8px_rgb(126_26_20/0.18)] hover:-translate-y-0.5 hover:opacity-95 active:translate-y-0 active:shadow-none",
};

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  className = "",
  children,
  disabled,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: "sm" | "md"; loading?: boolean }) {
  const sizing = size === "sm" ? "h-9 px-3.5" : "h-11 px-5";
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={`inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-semibold tracking-[0.015em] transition duration-200 disabled:translate-y-0 disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none ${sizing} ${VARIANT[variant]} ${className}`}
    >
      {loading && <Spinner />}
      {children}
    </button>
  );
}

/** Class string for a link that should look like a button. */
export function linkButton(variant: "primary" | "secondary" = "primary"): string {
  return `inline-flex h-11 items-center justify-center whitespace-nowrap rounded-lg px-5 text-sm font-semibold tracking-[0.015em] transition duration-200 hover:-translate-y-0.5 active:translate-y-0 ${
    variant === "primary"
      ? "bg-fill text-white shadow-[0_3px_8px_rgb(18_35_63/0.18)] hover:bg-fill-hover hover:shadow-[0_7px_16px_rgb(18_35_63/0.2)]"
      : "border border-line-strong bg-surface text-ink shadow-[0_2px_4px_rgb(20_30_54/0.05)] hover:border-navy/30 hover:bg-brand-soft"
  }`;
}

export function Spinner({ className = "" }: { className?: string }) {
  return (
    <span
      aria-hidden
      className={`inline-block h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent ${className}`}
    />
  );
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-xl border border-line/90 bg-surface shadow-[var(--shadow-paper)] ${className}`}>{children}</div>;
}

const ALERT: Record<string, string> = {
  info: "border-brand/40 border-l-brand bg-brand-soft/70",
  warn: "border-warn/30 border-l-warn bg-warn-soft",
  error: "border-bad/30 border-l-bad bg-bad-soft",
  ok: "border-ok/30 border-l-ok bg-ok-soft",
};
const ALERT_PREFIX: Record<string, string> = { info: "Note", warn: "Please note", error: "Problem", ok: "Done" };

/** Messages carry a text prefix so meaning never depends on colour alone. */
export function Alert({
  tone = "info",
  title,
  children,
  action,
}: {
  tone?: "info" | "warn" | "error" | "ok";
  title?: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div role={tone === "error" ? "alert" : "status"} className={`flex flex-wrap items-start justify-between gap-3 rounded-lg border border-l-4 px-4 py-3.5 text-sm shadow-[0_2px_8px_rgb(20_30_54/0.035)] ${ALERT[tone]}`}>
      <div>
        <p className="font-semibold">{title ?? ALERT_PREFIX[tone]}</p>
        {children && <div className="mt-0.5 text-ink/90">{children}</div>}
      </div>
      {action}
    </div>
  );
}

const BADGE: Record<string, string> = {
  neutral: "border-line-strong bg-canvas/70 text-muted",
  info: "border-navy/25 bg-brand-soft text-navy",
  warn: "border-warn/40 bg-warn-soft text-warn",
  ok: "border-ok/40 bg-ok-soft text-ok",
  bad: "border-bad/40 bg-bad-soft text-bad",
};

/**
 * A small status label: a level, rounded pill with a dot and the word in capitals. The word is always there, so colour
 * is never the only cue. One look for every status and state across the consoles; never rotated.
 */
export function Badge({ tone = "neutral", children }: { tone?: "neutral" | "info" | "warn" | "ok" | "bad"; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase leading-none tracking-[0.1em] ${BADGE[tone]}`}>
      <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-current" />
      {children}
    </span>
  );
}

/**
 * The appraisal's status as a {@link Badge}. With `queryRaised` (the HoD has messaged the faculty member during the
 * review) it reads "Query raised": the appraisal is still with the HoD, but waiting for the faculty member.
 */
export function StatusBadge({ status, queryRaised = false }: { status: Status; queryRaised?: boolean }) {
  if (status === "HOD_REVIEW" && queryRaised) return <Badge tone="warn">Query raised</Badge>;
  const info = STATUS_INFO[status];
  return <Badge tone={info.tone}>{info.label}</Badge>;
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden className={`animate-pulse rounded-md bg-line/60 ${className}`} />;
}

export function EmptyState({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="empty-state flex flex-col items-center gap-3 rounded-xl border border-dashed border-line-strong bg-surface/75 px-5 py-10 text-center text-sm text-muted shadow-[var(--shadow-paper)]">
      <span aria-hidden="true" className="flex h-11 w-11 items-center justify-center rounded-full border border-line bg-brand-soft/70 text-navy">
        <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 4.75h9.5A2.5 2.5 0 0 1 17 7.25v12H7.5A2.5 2.5 0 0 0 5 21.75z" />
          <path d="M5 4.75v17M17 8h2a1 1 0 0 1 1 1v10.25H7.5A2.5 2.5 0 0 0 5 21.75" />
        </svg>
      </span>
      <p className="max-w-lg font-display text-lg italic text-ink">{children}</p>
      {action}
    </div>
  );
}

/** The navy bars of the printed form (PART A, PART B ...), set in tracked small caps. */
export function SectionBar({ children }: { children: ReactNode }) {
  return <h2 className="rounded-md bg-fill px-4 py-2 font-sans text-[11px] font-bold uppercase tracking-[0.17em] text-white shadow-[0_2px_6px_rgb(18_35_63/0.12)]">{children}</h2>;
}

/** A section heading like the form's: serif title over a fine navy rule. */
export function RuledHeading({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <h2 id={id} className="border-b border-line-strong pb-2 font-display text-xl font-semibold tracking-tight text-navy">
      {children}
    </h2>
  );
}

/**
 * Progress as a ruler of eleven marks, one per form page, filled where the page has entries.
 * Honest by design: it shows where you have written something, not a made-up "percent complete".
 */
export function PageRuler({ counts, className = "" }: { counts: Record<string, number> | null; className?: string }) {
  const filled = PAGES.map((p) => p.sections.some((s) => (counts?.[s.key] ?? 0) > 0));
  const n = filled.filter(Boolean).length;
  return (
    <div className={className}>
      <ol className="flex gap-1" aria-label={`Form pages with entries: ${n} of ${PAGES.length}`}>
        {PAGES.map((p, i) => (
          <li key={p.slug} title={`${p.number} ${p.title}${filled[i] ? "" : " (no entries yet)"}`} className={`h-2.5 flex-1 rounded-[2px] ${filled[i] ? "bg-fill" : "bg-line"}`} />
        ))}
      </ol>
      <p className="mt-1.5 text-xs text-muted">
        <span className="font-semibold text-ink">{n}</span> of {PAGES.length} form pages have entries
      </p>
    </div>
  );
}
