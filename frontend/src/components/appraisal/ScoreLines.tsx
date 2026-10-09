import { formatNumber } from "@/lib/format";
import type { ScoreLine } from "@/lib/types";

/**
 * How a criterion's marks are worked out: the rate for each kind of entry, how many entries there are, and the marks they
 * earn. A kind of entry that has reached its limit says so, so the sum on the line is never a surprise.
 */
export function ScoreLines({ lines, label, quiet = false }: { lines: ScoreLine[]; label: string; quiet?: boolean }) {
  if (lines.length === 0) return null;
  return (
    <ul className={`${quiet ? "mt-1.5 text-xs" : "mt-3 text-sm"} space-y-0.5 text-muted`} aria-label={`How the marks for ${label} are worked out`}>
      {lines.map((l) => {
        const capped = l.cap !== null && l.count * l.perEntry > l.cap;
        return (
          <li key={l.description} className="flex justify-between gap-3 border-t border-dotted border-line pt-0.5">
            <span className={l.count === 0 ? undefined : "text-ink"}>
              {l.description} <span className="text-muted">({formatNumber(l.perEntry)} each)</span>
            </span>
            <span className="shrink-0 tabular-nums">
              {l.count} × {formatNumber(l.perEntry)}
              {capped ? `, stops at ${l.cap}` : ""} = <span className="font-semibold text-ink">{formatNumber(l.marks)}</span>
            </span>
          </li>
        );
      })}
    </ul>
  );
}
