"use client";

/** Small SVG charts for the administrator's dashboard. Nothing is loaded from outside: the application has no internet. */

export interface Slice {
  label: string;
  value: number;
  color: string;
}

/** Colours of the workflow statuses, in the order of the chain. They are always paired with text, never the only signal. */
export const STATUS_COLOUR: Record<string, string> = {
  DRAFT: "#8a96a8",
  SUBMITTED: "#5b8def",
  HOD_REVIEW: "#2f6fdb",
  HOD_APPROVED: "#8e6bd8",
  PRINCIPAL_REVIEW: "#d99a1c",
  APPROVED: "#2e9d5b",
};

/** A donut with the total in the middle and a legend that states every figure in words. */
export function DonutChart({ slices, centre, label }: { slices: Slice[]; centre: string; label: string }) {
  const total = slices.reduce((a, s) => a + s.value, 0);
  const R = 52;
  const C = 2 * Math.PI * R;
  let offset = 0;
  return (
    <figure className="flex flex-wrap items-center gap-6">
      <svg viewBox="0 0 140 140" role="img" aria-label={label} className="h-40 w-40 shrink-0">
        <circle cx="70" cy="70" r={R} fill="none" stroke="var(--color-line, #e3e6ec)" strokeWidth="18" />
        {total > 0 &&
          slices.filter((s) => s.value > 0).map((s) => {
            const length = (s.value / total) * C;
            const el = (
              <circle
                key={s.label}
                cx="70"
                cy="70"
                r={R}
                fill="none"
                stroke={s.color}
                strokeWidth="18"
                strokeDasharray={`${length} ${C - length}`}
                strokeDashoffset={-offset}
                transform="rotate(-90 70 70)"
              />
            );
            offset += length;
            return el;
          })}
        <text x="70" y="68" textAnchor="middle" className="fill-current text-[22px] font-semibold">{centre}</text>
        <text x="70" y="86" textAnchor="middle" className="fill-current text-[9px] opacity-70">{total === 0 ? "none yet" : "in all"}</text>
      </svg>
      <figcaption>
        <ul className="space-y-1 text-sm">
          {slices.map((s) => (
            <li key={s.label} className="flex items-center gap-2">
              <span aria-hidden className="inline-block h-3 w-3 rounded-sm" style={{ background: s.color }} />
              <span>{s.label}</span>
              <span className="ml-auto pl-4 font-semibold tabular-nums">{s.value}</span>
            </li>
          ))}
        </ul>
      </figcaption>
    </figure>
  );
}

export interface BarRow {
  label: string;
  /** Segments left to right; zero-length ones are skipped. */
  segments: Slice[];
  /** The width the row is drawn against (the largest total among the rows, or a fixed 100 for percentages). */
  note?: string;
}

/** Horizontal stacked bars, one row each, the figures in words beside every bar. */
export function StackedBars({ rows, max, label }: { rows: BarRow[]; max?: number; label: string }) {
  const scale = max ?? Math.max(1, ...rows.map((r) => r.segments.reduce((a, s) => a + s.value, 0)));
  return (
    <div role="img" aria-label={label} className="space-y-2">
      {rows.map((r) => {
        const total = r.segments.reduce((a, s) => a + s.value, 0);
        return (
          <div key={r.label} className="grid grid-cols-[6rem_1fr_auto] items-center gap-3 text-sm">
            <span className="truncate font-medium" title={r.label}>{r.label}</span>
            <div className="flex h-5 overflow-hidden rounded-sm bg-canvas">
              {r.segments.filter((s) => s.value > 0).map((s) => (
                <span key={s.label} title={`${s.label}: ${s.value}`} style={{ width: `${(s.value / scale) * 100}%`, background: s.color }} />
              ))}
            </div>
            <span className="tabular-nums text-muted">{r.note ?? total}</span>
          </div>
        );
      })}
    </div>
  );
}

export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted">
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-1.5">
          <span aria-hidden className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: i.color }} />
          {i.label}
        </li>
      ))}
    </ul>
  );
}
