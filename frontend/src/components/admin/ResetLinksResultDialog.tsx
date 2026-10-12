"use client";

import { Alert, Button } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import type { LinkResult } from "@/lib/types";

/**
 * What happened after reset links were sent to several users: how many went out, how many were left alone and how many
 * could not be e-mailed, with the user and the reason for each one that was not sent. No password appears anywhere.
 */
export function ResetLinksResultDialog({ result, onClose }: { result: LinkResult | null; onClose: () => void }) {
  return (
    <Dialog open={result !== null} onClose={onClose} title="Reset links" wide>
      {result && <Body result={result} onClose={onClose} />}
    </Dialog>
  );
}

function Body({ result, onClose }: { result: LinkResult; onClose: () => void }) {
  return (
    <div className="space-y-4">
      {!result.mailReady && (
        <Alert tone="error" title="This server cannot send e-mail">
          No link was issued because no mail server is set up (FAMS_MAIL_HOST, FAMS_MAIL_FROM and FAMS_PUBLIC_URL). People can still use
          &quot;Forgot your password?&quot; once it is, or you can reset a password from the user&apos;s own row.
        </Alert>
      )}
      <dl className="grid grid-cols-3 gap-3 text-center" aria-label="Result">
        <Tile label="Sent" value={result.sent} tone="text-ok" />
        <Tile label="Skipped" value={result.skipped} tone="text-warn" />
        <Tile label="Failed" value={result.failed} tone="text-bad" />
      </dl>
      <p className="text-sm text-muted">
        {result.requested} user{result.requested === 1 ? "" : "s"} chosen. Each link works once and expires in 30 minutes; the person chooses
        their own password with it, and their current password keeps working until then.
      </p>
      <List title="Skipped" rows={result.skippedUsers} />
      <List title="Failed" rows={result.failedUsers} />
      <div className="flex justify-end">
        <Button onClick={onClose}>Close</Button>
      </div>
    </div>
  );
}

function Tile({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className="rounded-lg border border-line bg-surface px-3 py-3">
      <dt className="text-[11px] font-bold uppercase tracking-[0.1em] text-muted">{label}</dt>
      <dd className={`mt-1 font-display text-3xl font-medium tabular-nums ${tone}`}>{value}</dd>
    </div>
  );
}

function List({ title, rows }: { title: string; rows: LinkResult["skippedUsers"] }) {
  if (rows.length === 0) return null;
  return (
    <section aria-label={`${title} users`}>
      <h3 className="mb-1 text-sm font-semibold text-navy">{title} ({rows.length})</h3>
      <ul className="max-h-48 divide-y divide-line overflow-y-auto rounded-lg border border-line text-sm">
        {rows.map((r) => (
          <li key={r.id} className="flex flex-wrap justify-between gap-x-4 px-3 py-1.5">
            <span className="break-all font-medium">{r.email || `User ${r.id}`}</span>
            <span className="text-muted">{r.reason}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
