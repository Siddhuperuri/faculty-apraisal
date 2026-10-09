"use client";

import Link from "next/link";
import { Card } from "@/components/ui/primitives";
import { formatNumber } from "@/lib/format";
import { useAppraisal } from "./AppraisalProvider";

/**
 * The marks this page's entries earn, worked out by the server and refreshed after every save, so the faculty member
 * sees them grow while they fill the page in. Where nothing is calculated (B2 to B4) it only says where the score goes.
 */
export function CriterionMarks({ criterion }: { criterion: string }) {
  const { appraisal, id } = useAppraisal();
  const row = appraisal?.scores.find((r) => r.criterion === criterion);
  if (!row) return null;

  const lines = row.breakdown;
  const adjusted = row.selfScore !== null && row.calculated !== null && row.selfScore !== row.calculated;

  return (
    <Card className="p-4 sm:p-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-brand">
            {row.reference} · Marks for this page
          </p>
          <p className="mt-0.5 text-sm text-muted">
            {lines.length > 0
              ? "Worked out from your entries and updated as you save."
              : "Marks for this criterion are entered on the Score & Review page."}
          </p>
        </div>
        <p className="text-right" aria-live="polite">
          <span className="font-display text-3xl font-medium tabular-nums text-navy">
            {row.score === null ? "—" : formatNumber(row.score)}
          </span>
          <span className="ml-1.5 text-sm text-muted">
            {row.maxMarks === null ? "no maximum" : `of ${row.maxMarks}`}
          </span>
        </p>
      </div>
      {lines.length > 0 && (
        <ul className="mt-3 space-y-0.5 text-sm" aria-label={`How the marks for ${row.label} are worked out`}>
          {lines.map((l) => (
            <li key={l.description} className="flex justify-between gap-3 border-t border-dotted border-line pt-0.5">
              <span className={l.count === 0 ? "text-muted" : undefined}>
                {l.description} <span className="text-muted">({l.perEntry} each)</span>
              </span>
              <span className="shrink-0 tabular-nums">
                {l.count} × {l.perEntry} = <span className="font-semibold text-ink">{l.marks}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
      {adjusted && (
        <p className="mt-2 text-xs text-muted">
          You have set your own score of {formatNumber(row.selfScore!)} on the{" "}
          <Link href={`/appraisals/${id}`} className="underline">Score &amp; Review</Link> page; the calculation is {formatNumber(row.calculated!)}.
        </p>
      )}
    </Card>
  );
}
