"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import { get } from "@/lib/api";
import { STATUS_INFO } from "@/lib/labels";
import type { HealthLevel, Status, YearReadiness } from "@/lib/types";

const MARK: Record<HealthLevel, { mark: string; word: string; cls: string }> = {
  ok: { mark: "✓", word: "In order", cls: "text-ok" },
  warn: { mark: "!", word: "Worth attention", cls: "text-warn" },
  problem: { mark: "✕", word: "Needs fixing", cls: "text-bad" },
};

const STATE_ORDER: { key: string; label: string }[] = [
  { key: "NOT_STARTED", label: "Not started" },
  { key: "DRAFT", label: "Draft" },
  { key: "SUBMITTED", label: STATUS_INFO.SUBMITTED.label },
  { key: "HOD_REVIEW", label: STATUS_INFO.HOD_REVIEW.label },
  { key: "HOD_APPROVED", label: STATUS_INFO.HOD_APPROVED.label },
  { key: "PRINCIPAL_REVIEW", label: STATUS_INFO.PRINCIPAL_REVIEW.label },
  { key: "APPROVED", label: STATUS_INFO.APPROVED.label },
];

/**
 * The guided checklist shown before an academic year is closed (or reopened): what stands in the way, with the counts of
 * appraisals by state. It advises and never blocks; when anything is not in order the administrator ticks that they have
 * read it before the button works.
 */
export function YearReadinessDialog({
  open,
  yearId,
  yearName,
  confirmLabel,
  onClose,
  onConfirm,
}: {
  open: boolean;
  yearId: number;
  yearName: string;
  /** Null: the dialog only shows the checklist. */
  confirmLabel: string | null;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onClose={onClose} title={`${yearName}: checklist`} wide>
      <Body yearId={yearId} confirmLabel={confirmLabel} onClose={onClose} onConfirm={onConfirm} />
    </Dialog>
  );
}

function Body({ yearId, confirmLabel, onClose, onConfirm }: { yearId: number; confirmLabel: string | null; onClose: () => void; onConfirm: () => void }) {
  const [data, setData] = useState<YearReadiness | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [understood, setUnderstood] = useState(false);

  useEffect(() => {
    let alive = true;
    get<YearReadiness>(`/api/admin/academic-years/${yearId}/readiness`)
      .then((r) => alive && setData(r))
      .catch((e) => alive && setError((e as Error).message));
    return () => {
      alive = false;
    };
  }, [yearId]);

  if (error) return <Alert tone="error" title="Could not load the checklist">{error}</Alert>;
  if (!data) return <div aria-busy="true"><Skeleton className="h-48 w-full" /></div>;

  const closing = data.mode === "CLOSING";
  return (
    <div className="space-y-5">
      <p className="text-sm text-muted">
        {closing
          ? "Closing a year stops new appraisals starting in it. Appraisals already started carry on and keep their year."
          : "Reopening a year lets faculty without an appraisal in it start theirs."}
      </p>

      <section aria-label="Appraisals by state">
        <h3 className="mb-2 text-[11px] font-bold uppercase tracking-[0.14em] text-muted">Appraisals in {data.name}</h3>
        <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {STATE_ORDER.map((s) => (
            <div key={s.key} className="rounded-lg border border-line bg-canvas/60 px-3 py-2">
              <dt className="text-xs text-muted">{s.label}</dt>
              <dd className="font-display text-2xl tabular-nums">{data.byState[s.key as Status | "NOT_STARTED"] ?? 0}</dd>
            </div>
          ))}
        </dl>
        {data.queryRaised > 0 && <p className="mt-2 text-xs text-muted">{data.queryRaised} of those with the HoD are waiting for the faculty member to meet the HoD.</p>}
      </section>

      <section aria-label="Checklist">
        <h3 className="mb-2 text-[11px] font-bold uppercase tracking-[0.14em] text-muted">{closing ? "Before closing" : "Before reopening"}</h3>
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface">
          {data.items.map((i) => (
            <li key={i.code} className="flex items-start gap-3 px-3.5 py-2.5 text-sm">
              <span aria-hidden className={`mt-0.5 w-4 shrink-0 text-center font-bold ${MARK[i.level].cls}`}>{MARK[i.level].mark}</span>
              <span><span className="sr-only">{MARK[i.level].word}: </span>{i.message}</span>
            </li>
          ))}
        </ul>
      </section>

      {confirmLabel && !data.ready && (
        <label className="flex items-start gap-2 text-sm">
          <input type="checkbox" checked={understood} onChange={(e) => setUnderstood(e.target.checked)} className="mt-1 h-4 w-4" />
          <span>I have read the points above and want to go ahead.</span>
        </label>
      )}

      <div className="flex flex-wrap justify-end gap-2 border-t border-line pt-4">
        <Button variant="secondary" onClick={onClose}>{confirmLabel ? "Cancel" : "Close"}</Button>
        {confirmLabel && <Button onClick={onConfirm} disabled={!data.ready && !understood}>{confirmLabel}</Button>}
      </div>
    </div>
  );
}
