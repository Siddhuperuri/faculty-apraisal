import { formatDateTime } from "@/lib/format";
import { ACTION_LABEL, roleLabel } from "@/lib/labels";
import type { HistoryRow } from "@/lib/types";

/**
 * Every step of the review, oldest first, with the comment recorded at each. For the faculty member (`forFaculty`) it
 * shows only the submissions (with their date and time) and that the HoD began the review, without a date or time. Everyone
 * else sees every step with its date and time.
 */
export function HistoryTimeline({ history, forFaculty = false }: { history: HistoryRow[]; forFaculty?: boolean }) {
  const rows = forFaculty ? history.filter((h) => h.action === "SUBMIT" || h.action === "RESUBMIT" || h.action === "START_HOD_REVIEW") : history;
  if (rows.length === 0) {
    return (
      <p className="text-sm text-muted">
        {forFaculty ? "Nothing to show yet. It appears here once you submit your appraisal." : "Nothing has happened yet. History appears here once the appraisal is submitted."}
      </p>
    );
  }
  return (
    <ol className="space-y-3">
      {rows.map((h, i) => (
        <li key={i} className="relative border-l-2 border-line-strong pl-5 before:absolute before:-left-[7px] before:top-1.5 before:h-3 before:w-3 before:rounded-full before:border-2 before:border-ochre before:bg-surface">
          <p className="font-display text-base font-medium">{ACTION_LABEL[h.action] ?? h.action}</p>
          <p className="text-xs text-muted">
            {/* The faculty member sees a date and time only on their own submission, not on the HoD's or Principal's steps. */}
            {!forFaculty || h.action === "SUBMIT" || h.action === "RESUBMIT" ? `${roleLabel(h.actorRole)} · ${formatDateTime(h.at)}` : roleLabel(h.actorRole)}
          </p>
          {h.comment && <blockquote className="mt-1 whitespace-pre-wrap rounded-sm border-l-2 border-ochre bg-canvas/80 px-3 py-2 font-display text-[15px] italic">{h.comment}</blockquote>}
        </li>
      ))}
    </ol>
  );
}
