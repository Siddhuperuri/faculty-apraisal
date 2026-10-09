"use client";

import { useEffect, useState } from "react";
import { ago } from "@/lib/format";
import { Spinner } from "@/components/ui/primitives";
import { useAppraisal } from "./AppraisalProvider";

/**
 * "Saved" appears only after the server has confirmed the write. A polite live region announces changes to
 * screen readers without stealing focus.
 */
export function SaveStatus() {
  const { saveState, saveMessage, lastSavedAt, retryFailedSave, appraisal } = useAppraisal();
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 30_000);
    return () => clearInterval(t);
  }, []);

  if (appraisal && !appraisal.editable) return null;

  let content: React.ReactNode;
  if (saveState === "saving") {
    content = (
      <span className="inline-flex items-center gap-2 text-muted">
        <Spinner /> Saving…
      </span>
    );
  } else if (saveState === "error") {
    content = (
      <span className="inline-flex flex-wrap items-center gap-2 text-bad">
        <span className="font-medium">Not saved.</span> {saveMessage ?? "Something went wrong."}
        <button type="button" onClick={retryFailedSave} className="inline-flex min-h-8 items-center rounded-full border border-bad/40 bg-surface px-3 text-xs font-semibold transition-colors hover:bg-bad-soft">
          Retry
        </button>
      </span>
    );
  } else if (lastSavedAt) {
    const when = ago(lastSavedAt, now);
    content = <span className="text-ok">{when === "just now" ? "Saved just now" : `Last saved ${when}`}</span>;
  } else {
    content = <span className="text-muted">Changes are saved automatically.</span>;
  }

  const stateStyle = saveState === "error"
    ? "border-bad/25 bg-bad-soft/70"
    : saveState === "saving"
      ? "border-line bg-surface text-muted"
      : lastSavedAt
        ? "border-ok/20 bg-ok-soft/70"
        : "border-line bg-surface text-muted";

  return (
    <div role="status" aria-live="polite" className={`inline-flex min-h-9 max-w-full items-center rounded-full border px-3 py-1 text-sm shadow-[0_1px_3px_rgb(20_30_54/0.04)] ${stateStyle}`}>
      {content}
    </div>
  );
}
