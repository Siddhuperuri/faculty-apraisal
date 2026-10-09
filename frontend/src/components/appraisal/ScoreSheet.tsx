"use client";

import { Alert } from "@/components/ui/primitives";
import { formatNumber } from "@/lib/format";
import type { ScoreRow } from "@/lib/types";
import { ScoreLines } from "./ScoreLines";

const TH = "px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.05em]";

/**
 * Item 11 of the form: the criteria, the maximum for this faculty member's cadre (Annexure A), the scoring components
 * that maximum is made of (Annexure B, where the college gives them) and the score. Every score is worked out by the
 * server from the entries and follows them as they are saved; nobody types one. B5 to B9 are marked per entry and have no
 * maximum.
 */
export function ScoreSheet({ scores, cadre, editable }: { scores: ScoreRow[]; cadre: string; editable: boolean }) {
  const total = scores.reduce((n, r) => n + r.score, 0);

  return (
    <div className="score-sheet space-y-3">
      <p className="font-display text-[15px] italic text-muted">
        Maximum marks for your cadre ({cadre}) follow Annexure A of the official form. Where the college breaks a maximum into scoring components (Annexure B), they are listed under the criterion. B5 to B9 are marked per entry and have no maximum.
      </p>
      <div className="overflow-x-auto rounded-md border border-line">
        <table className="w-full min-w-full table-fixed border-collapse text-sm">
          <caption className="sr-only">Self appraisal score sheet</caption>
          <thead>
            <tr className="bg-brand-soft text-navy">
              <th scope="col" className={`${TH} w-14 text-center`}>S. No</th>
              <th scope="col" className={`${TH} text-left`}>Criteria</th>
              <th scope="col" className={`${TH} text-right`}>Max. Marks (my cadre)</th>
              <th scope="col" className={`${TH} w-44 text-right`}>Score</th>
            </tr>
          </thead>
          <tbody aria-live="polite">
            {scores.map((r, i) => (
              <tr key={r.criterion} className="border-t border-line align-top">
                <td className="px-3 py-2 text-center font-display text-base tabular-nums text-muted">{i + 1}</td>
                <td className="px-3 py-2">
                  <p>{r.label}</p>
                  {r.components.length > 0 && (
                    <ul className="mt-1.5 space-y-0.5 text-xs text-muted" aria-label={`Scoring components of ${r.label} for your cadre`}>
                      {r.components.map((c) => (
                        <li key={c.description} className="flex justify-between gap-3 border-t border-dotted border-line pt-0.5">
                          <span>{c.description}</span>
                          <span className="shrink-0 tabular-nums font-semibold text-ink">
                            {c.awarded != null ? `${formatNumber(c.awarded)} of ${c.maxMarks}` : c.maxMarks}
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}
                  <ScoreLines lines={r.breakdown} label={r.label} quiet />
                </td>
                <td className="px-3 py-2 text-right font-display text-lg tabular-nums">
                  {r.maxMarks === null ? <span className="font-sans text-xs italic text-muted">per entry, no maximum</span> : r.maxMarks}
                </td>
                <td className="px-3 py-2 text-right">
                  <span className="font-display text-lg tabular-nums">{formatNumber(r.score)}</span>
                </td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-navy bg-canvas/70">
              <td colSpan={2} className="px-3 py-2 text-center text-[11px] font-bold uppercase tracking-[0.2em]">Total</td>
              <td className="px-3 py-2" />
              <td className="px-3 py-2 text-right font-display text-xl font-semibold tabular-nums text-navy" aria-live="polite">
                {formatNumber(Math.round(total * 100) / 100)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      {editable ? (
        <Alert tone="info" title="Your marks are worked out for you">
          Every mark here is calculated from the entries you make in the form and changes as soon as an entry is saved. Teaching &amp; Learning earns an eighth of its maximum for each course (up to 8 courses);
          the other criteria earn marks for each entry, and B2 to B4 stop at their maximum. There is nothing to type on this page.
        </Alert>
      ) : (
        <p className="text-xs text-muted">Marks are calculated from the entries.</p>
      )}
    </div>
  );
}
