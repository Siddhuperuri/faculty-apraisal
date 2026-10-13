import { formatDateTime } from "@/lib/format";
import { ACTION_LABEL, roleLabel } from "@/lib/labels";
import type { HistoryRow } from "@/lib/types";

/**
 * Every step of the review, oldest first, with the comment recorded at each. It is shown to the reviewers (Head of the
 * Department, Principal, Director Technical) only: the faculty member is never sent the history and has no such panel.
 */
export function HistoryTimeline({ history }: { history: HistoryRow[] }) {
  if (history.length === 0) {
    return <p className="text-sm text-muted">Nothing has happened yet. History appears here once the appraisal is submitted.</p>;
  }
  return (
    <ol className="space-y-3">
      {history.map((h, i) => (
        <li key={i} className="relative border-l-2 border-line-strong pl-5 before:absolute before:-left-[7px] before:top-1.5 before:h-3 before:w-3 before:rounded-full before:border-2 before:border-ochre before:bg-surface">
          <p className="font-display text-base font-medium">{ACTION_LABEL[h.action] ?? h.action}</p>
          <p className="text-xs text-muted">{`${roleLabel(h.actorRole)} · ${formatDateTime(h.at)}`}</p>
          {h.comment && <blockquote className="mt-1 whitespace-pre-wrap rounded-sm border-l-2 border-ochre bg-canvas/80 px-3 py-2 font-display text-[15px] italic">{h.comment}</blockquote>}
        </li>
      ))}
    </ol>
  );
}
