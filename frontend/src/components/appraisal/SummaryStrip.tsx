import { formatNumber, formatRupees } from "@/lib/format";
import type { SummaryFormat } from "@/lib/formStructure";

function show(value: number | null | undefined, format: SummaryFormat): string {
  if (value == null) return "—";
  if (format === "rupees") return formatRupees(value);
  if (format === "percent") return `${formatNumber(value)}%`;
  return formatNumber(value);
}

/** The totals the official form prints above or below a table, computed by the server from the saved rows. */
export function SummaryStrip({
  items,
  summary,
}: {
  items: { key: string; label: string; format: SummaryFormat }[];
  summary: Record<string, number | null> | null;
}) {
  return (
    <dl className="grid grid-cols-2 gap-3 rounded-xl border border-line bg-canvas/75 p-3 sm:grid-cols-3 sm:p-4 lg:grid-cols-4">
      {items.map((it) => (
        <div key={it.key} className="rounded-lg border border-line/80 border-t-2 border-t-navy/70 bg-surface/80 px-3 py-2.5 shadow-[0_1px_3px_rgb(20_30_54/0.035)]">
          <dt className="text-[10px] font-bold uppercase leading-tight tracking-[0.13em] text-muted">{it.label}</dt>
          <dd className="mt-1 font-display text-3xl font-medium leading-tight tabular-nums text-navy">{show(summary?.[it.key], it.format)}</dd>
        </div>
      ))}
    </dl>
  );
}
