"use client";

import { useEffect, useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { ApiError, post } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { PAGES } from "@/lib/formStructure";
import { useAppraisal } from "./AppraisalProvider";

/** The wording is the declaration printed on the official form. */
const DECLARATION =
  "I hereby declare that the information furnished in this report is true and correct to the best of my knowledge, and that supporting documents are available for verification.";

export function SubmitPanel() {
  const { id, counts, refreshAppraisal, saveState, saveMessage, appraisal } = useAppraisal();
  const [accepted, setAccepted] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  const blockers = appraisal?.submitBlockers ?? [];
  // What is missing comes from the server and is re-read whenever this panel is shown, after the earlier pages' autosaves.
  useEffect(() => {
    void refreshAppraisal();
  }, [refreshAppraisal]);
  const today = formatDate(new Date().toISOString().slice(0, 10));
  const blocked = saveState === "saving" ? "Waiting for your latest changes to finish saving…" : saveState === "error" ? `A recent change was not saved: ${saveMessage ?? "unknown error"}. Fix this first, then submit.` : null;
  const ready = accepted && !blocked && blockers.length === 0;

  const submit = async () => {
    setBusy(true);
    setError(null);
    setErrors({});
    try {
      await post(`/api/appraisals/${id}/submit`, { declarationAccepted: accepted });
      setConfirming(false);
      await refreshAppraisal();
    } catch (e) {
      setConfirming(false);
      if (e instanceof ApiError) {
        setErrors(e.fieldErrors ?? {});
        setError(e.fieldErrors ? "Please check the declaration below." : e.message);
      } else {
        setError("Could not submit. Please try again.");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-5">
      <div className="rounded-xl border border-line bg-canvas/50 p-4 sm:p-5">
        <h3 className="font-display text-lg font-semibold text-navy">What will be submitted</h3>
        <ul className="mt-3 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
          {PAGES.map((p) => {
            const n = p.sections.reduce((t, s, i, arr) => (arr.findIndex((x) => x.key === s.key) === i ? t + (counts[s.key] ?? 0) : t), 0);
            return (
              <li key={p.slug} className="flex justify-between gap-3 border-b border-line py-1">
                <span>{p.number} {p.title}</span>
                <span className={n === 0 ? "text-muted" : "font-medium"}>{n === 0 ? "No entries" : `${n} ${n === 1 ? "entry" : "entries"}`}</span>
              </li>
            );
          })}
        </ul>
        <p className="mt-2 text-xs text-muted">Sections with no entries are submitted as they are. Nothing can be changed, added or removed after you submit.</p>
      </div>

      <fieldset className="space-y-4 rounded-xl border border-line-strong/70 bg-surface p-4 shadow-[var(--shadow-paper)] sm:p-5">
        <legend className="px-1 text-sm font-semibold text-navy">Declaration</legend>
        <p className="text-sm">{DECLARATION}</p>
        <div className="flex items-start gap-2">
          <input id="declare" type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} aria-describedby={errors.declarationAccepted ? "declare-err" : undefined} className="mt-1 h-4 w-4" />
          <label htmlFor="declare" className="text-sm">I make this declaration.</label>
        </div>
        {errors.declarationAccepted && <p id="declare-err" className="text-sm font-medium text-bad">{errors.declarationAccepted}</p>}
        <div>
          <p className="mb-1 text-sm font-medium">Date</p>
          <p className="rounded-sm border border-line bg-canvas px-3 py-2 text-sm sm:max-w-xs">{today}</p>
          <p className="mt-1 text-xs text-muted">Recorded automatically when you submit.</p>
        </div>
      </fieldset>

      {error && <Alert tone="error" title="Not submitted">{error}</Alert>}
      {blocked && <Alert tone={saveState === "error" ? "error" : "info"} title={saveState === "error" ? "Unsaved change" : "Please wait"}>{blocked}</Alert>}

      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={() => setConfirming(true)} disabled={!ready}>Submit to your HoD</Button>
        {!ready && !blocked && <p className="text-sm text-muted">{blockers.length > 0 ? "Complete the items listed at the top of this page, then tick the declaration." : "Tick the declaration to submit."}</p>}
      </div>

      <ConfirmDialog
        open={confirming}
        title="Submit your appraisal?"
        confirmLabel={busy ? "Submitting…" : "Yes, submit"}
        onCancel={() => setConfirming(false)}
        onConfirm={() => void submit()}
        message={<p>It goes to your Head of the Department, and then to the Principal or the Director Technical. Once submitted it cannot be edited or taken back, so check it first. Do you want to submit now?</p>}
      />
    </div>
  );
}
