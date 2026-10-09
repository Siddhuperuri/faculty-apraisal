"use client";

import { Card } from "@/components/ui/primitives";
import { formatNumber } from "@/lib/format";
import { useAppraisal } from "./AppraisalProvider";
import { ScoreLines } from "./ScoreLines";

/**
 * The marks this page's entries earn, worked out by the server and refreshed after every save, so the faculty member
 * sees them grow while they fill the page in. They are the same marks the Score & Review page shows.
 */
export function CriterionMarks({ criterion }: { criterion: string }) {
  const { appraisal } = useAppraisal();
  const row = appraisal?.scores.find((r) => r.criterion === criterion);
  if (!row) return null;

  return (
    <Card className="p-4 sm:p-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] font-semibold uppercase tracking-[0.2em] text-brand">
            {row.reference} · Marks for this page
          </p>
          <p className="mt-0.5 text-sm text-muted">Worked out from your entries and updated as you save.</p>
        </div>
        <p className="text-right" aria-live="polite">
          <span className="font-display text-3xl font-medium tabular-nums text-navy">{formatNumber(row.score)}</span>
          <span className="ml-1.5 text-sm text-muted">
            {row.maxMarks === null ? "no maximum" : `of ${row.maxMarks}`}
          </span>
        </p>
      </div>
      <ScoreLines lines={row.breakdown} label={row.label} />
    </Card>
  );
}
