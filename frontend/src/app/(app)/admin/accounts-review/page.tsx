"use client";

import { useCallback, useEffect, useState } from "react";
import { ConsoleHeader, StatTile } from "@/components/console/parts";
import { Alert, Badge, Button, Card, EmptyState, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { ApiError, get, put } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/labels";
import type { AccountHygiene, HygienePerson, ImportHistoryEntry, InactiveAccount } from "@/lib/types";

const PERIODS = [
  { days: 30, label: "30 days" },
  { days: 60, label: "60 days" },
  { days: 90, label: "90 days (3 months)" },
  { days: 180, label: "180 days (6 months)" },
  { days: 365, label: "1 year" },
];

function People({ accounts }: { accounts: HygienePerson[] }) {
  return (
    <ul className="space-y-0.5">
      {accounts.map((a) => (
        <li key={a.id}>
          <span className="font-medium">{a.name}</span> <span className="text-muted">· {a.email} · {roleLabel(a.role)}</span>
        </li>
      ))}
    </ul>
  );
}

export default function AccountReviewPage() {
  const [hygiene, setHygiene] = useState<AccountHygiene | null>(null);
  const [imports, setImports] = useState<ImportHistoryEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      const [h, i] = await Promise.all([get<AccountHygiene>("/api/admin/account-hygiene"), get<ImportHistoryEntry[]>("/api/admin/imports")]);
      setHygiene(h);
      setImports(i);
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void load();
  }, [load]);

  if (error) {
    return <Alert tone="error" title="Could not load the account review" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!hygiene || !imports) {
    return (
      <div className="space-y-4" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-12 w-1/2" />
        <div className="grid gap-3 sm:grid-cols-5"><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /></div>
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  const duplicates = hygiene.duplicateEmployeeIds.length + hygiene.duplicateContacts.length;
  const gaps = hygiene.missingAssignments.length;

  return (
    <div className="space-y-8">
      <ConsoleHeader eyebrow="Administration" title="Account review" aside={<RefreshButton onRefresh={load} loadedAt={loadedAt} />}>
        Accounts to tidy: unused, duplicated, incomplete or left behind. Nothing here changes by itself.
      </ConsoleHeader>

      <div className="rise grid gap-3 sm:grid-cols-2 lg:grid-cols-5" style={{ "--i": 1 } as React.CSSProperties}>
        <StatTile label="Never signed in" value={hygiene.neverSignedIn} tone={hygiene.neverSignedIn > 0 ? "warn" : "plain"} hint="Active accounts never used" />
        <StatTile label="Still on the old password" value={hygiene.temporaryPassword} tone={hygiene.temporaryPassword > 0 ? "warn" : "plain"} hint="Must choose their own at sign-in" />
        <StatTile label="Disabled" value={hygiene.disabled} hint="Cannot sign in" />
        <StatTile label="Possible duplicates" value={duplicates} tone={duplicates > 0 ? "warn" : "ok"} hint="Employee IDs or contact numbers shared" />
        <StatTile label="Missing assignment" value={gaps} tone={gaps > 0 ? "warn" : "ok"} hint="No department where one is needed" />
      </div>

      <section aria-labelledby="dup-h" className="space-y-3">
        <RuledHeading id="dup-h">Possible duplicates and gaps</RuledHeading>
        {duplicates === 0 && gaps === 0 ? (
          <Alert tone="ok" title="Nothing to tidy">No two active accounts share an employee ID or contact number, and every faculty member and Head of the Department has a department.</Alert>
        ) : (
          <Card className="divide-y divide-line">
            {hygiene.duplicateEmployeeIds.map((d) => (
              <div key={`id-${d.value}`} className="space-y-1 p-4 text-sm">
                <p><Badge tone="warn">Same employee ID</Badge> <span className="ml-2 font-mono">{d.value}</span></p>
                <People accounts={d.accounts} />
              </div>
            ))}
            {hygiene.duplicateContacts.map((d) => (
              <div key={`c-${d.value}`} className="space-y-1 p-4 text-sm">
                <p><Badge tone="warn">Same contact number</Badge> <span className="ml-2 font-mono">{d.value}</span></p>
                <People accounts={d.accounts} />
              </div>
            ))}
            {hygiene.missingAssignments.map((g) => (
              <div key={`g-${g.account.id}`} className="space-y-1 p-4 text-sm">
                <p><Badge tone="bad">{g.problem}</Badge></p>
                <People accounts={[g.account]} />
              </div>
            ))}
          </Card>
        )}
        <p className="text-xs text-muted">Employee IDs are compared ignoring capitals, spaces and punctuation, and contact numbers by their last ten digits. Fix a record from the Accounts page; disable a duplicate below or there.</p>
      </section>

      <InactiveReview onChanged={load} />

      <section aria-labelledby="imp-h" className="space-y-3">
        <RuledHeading id="imp-h">Import history</RuledHeading>
        {imports.length === 0 ? (
          <EmptyState>No file has been imported yet. Use &ldquo;Add accounts from a file&rdquo; on the Accounts page.</EmptyState>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
            <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
              <caption className="sr-only">Account imports, newest first</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  {["When", "By", "Rows in file", "Accounts created", "Rows refused", "Result", ""].map((h, i) => (
                    <th key={i} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h || <span className="sr-only">Error report</span>}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {imports.map((i) => (
                  <tr key={i.id} className="border-t border-line">
                    <td className="px-4 py-2.5 tabular-nums">{formatDateTime(i.importedAt)}</td>
                    <td className="px-4 py-2.5">{i.administrator}</td>
                    <td className="px-4 py-2.5 tabular-nums">{i.rowsInFile}</td>
                    <td className="px-4 py-2.5 tabular-nums">{i.createdCount}</td>
                    <td className="px-4 py-2.5 tabular-nums">{i.rejectedRows}</td>
                    <td className="px-4 py-2.5">{i.outcome === "CREATED" ? <Badge tone="ok">Created</Badge> : <Badge tone="bad">Refused</Badge>}</td>
                    <td className="px-4 py-2.5 text-right">
                      {i.hasReport && (
                        <a href={`/api/admin/imports/${i.id}/report`} download className="font-medium text-brand hover:underline">
                          Download the list of problems<span className="sr-only"> for the import of {formatDateTime(i.importedAt)}</span>
                        </a>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-xs text-muted">An import is all or nothing: a refused file creates no accounts. The most recent 50 imports are kept.</p>
      </section>
    </div>
  );
}

/** Accounts nobody has signed in to for a chosen period, each with a way to disable it. */
function InactiveReview({ onChanged }: { onChanged: () => Promise<void> }) {
  const [days, setDays] = useState(90);
  const [rows, setRows] = useState<InactiveAccount[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [target, setTarget] = useState<InactiveAccount | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await get<InactiveAccount[]>(`/api/admin/inactive-accounts?days=${days}`));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [days]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reload when the period changes
    void load();
  }, [load]);

  const disable = async () => {
    if (!target) return;
    try {
      await put(`/api/admin/users/${target.id}`, { status: "DISABLED" });
      setNotice(`${target.name} was disabled. Their sessions have ended.`);
      setError(null);
      await Promise.all([load(), onChanged()]);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not disable the account.");
    } finally {
      setTarget(null);
    }
  };

  return (
    <section aria-labelledby="inactive-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <RuledHeading id="inactive-h">Inactive accounts</RuledHeading>
        <div>
          <label htmlFor="inactive-days" className="mb-1 block text-sm font-medium">Not signed in for</label>
          <select id="inactive-days" value={days} onChange={(e) => { setDays(Number(e.target.value)); setNotice(null); }} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
            {PERIODS.map((p) => <option key={p.days} value={p.days}>{p.label}</option>)}
          </select>
        </div>
      </div>
      {notice && <Alert tone="ok" title="Done">{notice}</Alert>}
      {error && <Alert tone="error" title="Problem">{error}</Alert>}
      {!rows ? (
        <Skeleton className="h-24 w-full" />
      ) : rows.length === 0 ? (
        <Alert tone="ok" title="No inactive accounts">Every active account has been used within the last {days} days.</Alert>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
          <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
            <caption className="sr-only">Active accounts not used for {days} days</caption>
            <thead>
              <tr className="bg-brand-soft text-navy">
                {["Account", "Role", "Department", "Last signed in", ""].map((h, i) => (
                  <th key={i} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h || <span className="sr-only">Action</span>}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t border-line">
                  <td className="px-4 py-2.5"><span className="font-display text-base font-medium">{r.name}</span><span className="block text-xs text-muted">{r.email}</span></td>
                  <td className="px-4 py-2.5">{roleLabel(r.role)}</td>
                  <td className="px-4 py-2.5">{r.department ?? "—"}</td>
                  <td className="px-4 py-2.5">{r.lastLoginAt ? formatDateTime(r.lastLoginAt) : <span className="text-warn">Never (created {formatDateTime(r.createdAt)})</span>}</td>
                  <td className="px-4 py-2.5 text-right">
                    <Button variant="secondary" size="sm" className="text-bad" onClick={() => setTarget(r)}>Disable<span className="sr-only"> {r.name}</span></Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-xs text-muted">An account never used is counted from the day it was created. Disabling ends the person&apos;s sessions at once; an administrator can enable the account again.</p>
      <ConfirmDialog
        open={target !== null}
        title="Disable this account?"
        destructive
        confirmLabel="Disable"
        onCancel={() => setTarget(null)}
        onConfirm={() => void disable()}
        message={<p>{target?.name} ({target?.email}) will no longer be able to sign in. Their appraisal records are kept.</p>}
      />
    </section>
  );
}
