"use client";

import { useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { roleLabel } from "@/lib/labels";
import type { BulkResetResult } from "@/lib/types";

/** Asks before the passwords of every selected user are set back to the standard one. */
export function BulkResetConfirm({
  open,
  count,
  working,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  count: number;
  working: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <ConfirmDialog
      open={open}
      destructive
      title={`Reset ${count} password${count === 1 ? "" : "s"}?`}
      confirmLabel={working ? "Resetting…" : `Yes, reset ${count === 1 ? "it" : "all"}`}
      onCancel={onCancel}
      onConfirm={onConfirm}
      message={
        <div className="space-y-2">
          <p>
            Each selected user&apos;s password is set back to the standard one, they are signed out everywhere, and they must
            choose a new password when they next sign in.
          </p>
          <p className="text-muted">Your own account is never reset here. Disabled users are reset too, but stay disabled.</p>
        </div>
      }
    />
  );
}

/** The result of a bulk reset: who was reset and who was left alone, with the standard password shown once. */
export function BulkResetResultDialog({ result, onClose }: { result: BulkResetResult | null; onClose: () => void }) {
  return (
    <Dialog open={result !== null} onClose={onClose} title="Passwords reset" wide>
      {result && <ResultBody result={result} onDone={onClose} />}
    </Dialog>
  );
}

function ResultBody({ result, onDone }: { result: BulkResetResult; onDone: () => void }) {
  const [copied, setCopied] = useState<"yes" | "no" | null>(null);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied("yes");
    } catch {
      setCopied("no");
    }
  };
  return (
    <div className="space-y-4">
      <p className="text-sm">
        {result.resetCount} of {result.requested} password{result.requested === 1 ? " was" : "s were"} set back to the standard one.
      </p>
      {result.resetCount > 0 && (
        <div>
          <p className="mb-1 text-[13px] font-semibold tracking-wide">Standard password</p>
          <code className="block select-all rounded-sm border border-line-strong bg-canvas px-3 py-3 text-center font-mono text-2xl font-semibold tracking-[0.12em] text-navy">
            {result.temporaryPassword}
          </code>
          <div className="mt-2 flex items-center gap-3">
            <Button type="button" variant="secondary" size="sm" onClick={() => void copy()}>Copy password</Button>
            <span role="status" className="text-sm">
              {copied === "yes" ? "Copied." : copied === "no" ? "Could not copy. Select it and copy it yourself." : ""}
            </span>
          </div>
          <p className="mt-2 text-sm text-muted">Each user must change it at their next sign-in. This is the only time it is shown here.</p>
        </div>
      )}
      {result.reset.length > 0 && (
        <details className="rounded-lg border border-line px-3 py-2 text-sm">
          <summary className="cursor-pointer font-medium">Reset ({result.reset.length})</summary>
          <ul className="mt-2 space-y-1">
            {result.reset.map((u) => (
              <li key={u.id} className="flex flex-wrap justify-between gap-2">
                <span>{u.email}</span>
                <span className="text-muted">{roleLabel(u.role)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {result.skipped.length > 0 && (
        <Alert tone="warn" title={`${result.skipped.length} left alone`}>
          <ul className="space-y-1">
            {result.skipped.map((u) => (
              <li key={u.id}>
                <span className="font-medium">{u.email || `User ${u.id}`}</span>: {u.reason}
              </li>
            ))}
          </ul>
        </Alert>
      )}
      <div className="flex justify-end">
        <Button onClick={onDone}>Done</Button>
      </div>
    </div>
  );
}
