"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert } from "@/components/ui/primitives";
import { formatNumber } from "@/lib/format";
import type { ScoreRow } from "@/lib/types";
import { validateScore } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
import { useAutosave } from "./useAutosave";

const TH = "px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.05em]";

/**
 * Item 11 of the form: the criteria, the maximum for this faculty member's cadre (Annexure A), the scoring components
 * that maximum is made of (Annexure B, where the college gives them) and the score. Criteria, maxima, components and
 * the marks calculated from the entries all come from the server. B5 to B9 are marked per entry and have no maximum.
 * The faculty member may type their own score over a calculated one (clearing it returns to the calculation); the
 * sheet checks it is a number from 0 up to the maximum where there is one. Entries save by themselves, and only
 * when valid.
 */
export function ScoreSheet({ scores, cadre, editable }: { scores: ScoreRow[]; cadre: string; editable: boolean }) {
  const { saveScores, setDirty } = useAppraisal();
  const [edits, setEdits] = useState<Record<string, string> | null>(null);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [serverErrors, setServerErrors] = useState<Record<string, string>>({});

  const shown = (r: ScoreRow): string => edits?.[r.criterion] ?? (r.selfScore == null ? "" : formatNumber(r.selfScore));
  /** The marks that count on screen: what is typed if it is a valid number, otherwise the calculation. */
  const counted = (r: ScoreRow): number | null => {
    const v = shown(r).trim();
    if (v !== "" && !validateScore(v, r.maxMarks, r.label)) return Number(v);
    return v === "" ? r.calculated : r.score;
  };

  const errors = useMemo(() => {
    const out: Record<string, string> = {};
    if (!edits) return out;
    for (const r of scores) {
      if (r.criterion in edits) {
        const e = validateScore(edits[r.criterion], r.maxMarks, r.label);
        if (e) out[r.criterion] = e;
      }
    }
    return out;
  }, [edits, scores]);
  const canSave = edits !== null && Object.keys(errors).length === 0;

  const save = useCallback(() => {
    if (!edits) return;
    const snapshot = edits;
    void saveScores(snapshot).then((r) => {
      if (r.ok) {
        setEdits((now) => (now === snapshot ? null : now)); // typed more meanwhile? keep it
        setServerErrors({});
      } else {
        const mapped: Record<string, string> = {};
        for (const [k, v] of Object.entries(r.error.fieldErrors ?? {})) mapped[k.replace(/^scores\./, "")] = v;
        setServerErrors(mapped);
      }
    });
  }, [edits, saveScores]);

  useAutosave({ pending: edits !== null, canSave, save });

  useEffect(() => {
    setDirty("scores#sheet", edits !== null);
    return () => setDirty("scores#sheet", false);
  }, [edits, setDirty]);

  // Totals follow what is on screen.
  const counting = scores.filter((r) => counted(r) !== null);
  const fixedTotal = scores.reduce((n, r) => n + (r.maxMarks ?? 0), 0);
  const selfTotal = counting.reduce((n, r) => n + (counted(r) ?? 0), 0);
  const applicable = scores.filter((r) => r.maxMarks === null || r.maxMarks > 0);

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
          <tbody>
            {scores.map((r, i) => {
              const err = serverErrors[r.criterion] ?? (touched[r.criterion] ? errors[r.criterion] : undefined);
              const id = `score-${r.criterion}`;
              return (
                <tr key={r.criterion} className="border-t border-line align-top">
                  <td className="px-3 py-2 text-center font-display text-base tabular-nums text-muted">{i + 1}</td>
                  <td className="px-3 py-2">
                    <label htmlFor={editable && (r.maxMarks === null || r.maxMarks > 0) ? id : undefined}>{r.label}</label>
                    {r.components.length > 0 && (
                      <ul className="mt-1.5 space-y-0.5 text-xs text-muted" aria-label={`Scoring components of ${r.label} for your cadre`}>
                        {r.components.map((c) => (
                          <li key={c.description} className="flex justify-between gap-3 border-t border-dotted border-line pt-0.5">
                            <span>{c.description}</span>
                            <span className="shrink-0 tabular-nums font-semibold text-ink">
                              {c.awarded != null ? `${c.awarded} of ${c.maxMarks}` : c.maxMarks}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                    {r.breakdown.length > 0 && (
                      <ul className="mt-1.5 space-y-0.5 text-xs text-muted" aria-label={`How the marks for ${r.label} are worked out`}>
                        {r.breakdown.map((l) => (
                          <li key={l.description} className="flex justify-between gap-3 border-t border-dotted border-line pt-0.5">
                            <span>{l.description} ({l.perEntry} each)</span>
                            <span className="shrink-0 tabular-nums">{l.count} × {l.perEntry} = <span className="font-semibold text-ink">{l.marks}</span></span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                  <td className="px-3 py-2 text-right font-display text-lg tabular-nums">
                    {r.maxMarks === null ? <span className="font-sans text-xs italic text-muted">per entry, no maximum</span> : r.maxMarks}
                  </td>
                  <td className="px-3 py-1.5 text-right">
                    {editable && (r.maxMarks === null || r.maxMarks > 0) ? (
                      <>
                        <input
                          id={id}
                          type="text"
                          inputMode="decimal"
                          autoComplete="off"
                          value={shown(r)}
                          placeholder={r.calculated === null ? undefined : formatNumber(r.calculated)}
                          aria-invalid={err ? true : undefined}
                          aria-describedby={err ? `${id}-err` : undefined}
                          onChange={(e) => setEdits({ ...(edits ?? {}), [r.criterion]: e.target.value })}
                          onBlur={() => setTouched((t) => ({ ...t, [r.criterion]: true }))}
                          className={`w-24 rounded-sm border bg-surface px-2 py-1 text-right font-display text-lg tabular-nums shadow-[inset_0_1px_2px_rgb(20_30_54/0.06)] focus:border-brand focus:shadow-[0_0_0_3px_rgb(0_112_192/0.15)] ${err ? "border-bad" : "border-line-strong"}`}
                        />
                        {err && <p id={`${id}-err`} className="mt-1 text-right text-xs font-medium text-bad">{err}</p>}
                        {r.calculated !== null && (
                          <p className="mt-1 text-right text-xs text-muted">
                            Calculated: <span className="tabular-nums font-semibold text-ink">{formatNumber(r.calculated)}</span>
                            {shown(r).trim() !== "" && (
                              <button
                                type="button"
                                className="ml-2 underline"
                                onClick={() => setEdits({ ...(edits ?? {}), [r.criterion]: "" })}
                              >
                                Use calculated
                              </button>
                            )}
                          </p>
                        )}
                      </>
                    ) : r.maxMarks === 0 ? (
                      <span className="font-display text-base italic text-muted" title="Not applicable for your cadre (maximum 0)">n/a</span>
                    ) : (
                      <span className="font-display text-lg tabular-nums">{r.score === null ? <span className="text-muted">—</span> : formatNumber(r.score)}</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="border-t-2 border-navy bg-canvas/70">
              <td colSpan={2} className="px-3 py-2 text-center text-[11px] font-bold uppercase tracking-[0.2em]">Total</td>
              <td className="px-3 py-2 text-right font-display text-xl font-semibold tabular-nums text-navy">{fixedTotal}</td>
              <td className="px-3 py-2 text-right font-display text-xl font-semibold tabular-nums text-navy">
                {counting.length === 0 ? <span className="text-muted">—</span> : formatNumber(Math.round(selfTotal * 100) / 100)}
              </td>
            </tr>
          </tfoot>
        </table>
      </div>
      <p className="text-xs text-muted" aria-live="polite">
        {counting.filter((r) => applicable.includes(r)).length} of {applicable.length} criteria scored. The total adds the marks counted so far.
      </p>
      {editable ? (
        <Alert tone="info" title="Marks are calculated from your entries">
          The workload part of Teaching &amp; Learning (2.5 marks a course) and criteria B5 to B9 (a fixed number of marks for each entry) are calculated for you and update as you fill the form in.
          You may type a different score over a calculated one; clear it to go back to the calculation. B2 to B4 and the rest of B1 are scored by you, from 0 up to the maximum.
        </Alert>
      ) : (
        <p className="text-xs text-muted">Marks are calculated from the entries, or are the faculty member&apos;s own score where they have set one.</p>
      )}
    </div>
  );
}
