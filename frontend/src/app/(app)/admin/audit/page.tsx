"use client";

import { useCallback, useEffect, useState } from "react";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { Alert, Button, EmptyState, Skeleton } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { del, get } from "@/lib/api";
import { AUDIT_FILTER_ACTIONS, auditActionLabel, describeDetails } from "@/lib/adminLabels";
import { formatDateTime } from "@/lib/format";
import type { AuditPage } from "@/lib/types";

const PAGE_SIZE = 25;

export default function AuditPage() {
  const [data, setData] = useState<AuditPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [action, setAction] = useState("");
  const [pending, setPending] = useState<{ id?: number } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), size: String(PAGE_SIZE) });
    if (action) params.set("action", action);
    try {
      setData(await get<AuditPage>(`/api/admin/audit?${params}`));
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [page, action, stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load when the page or filter changes
    void load();
  }, [load]);

  const remove = async () => {
    if (!pending) return;
    try {
      const params = new URLSearchParams();
      if (action && pending.id === undefined) params.set("action", action);
      const r = await del<{ removed: number }>(pending.id === undefined ? `/api/admin/audit?${params}` : `/api/admin/audit/${pending.id}`);
      setNotice(`${r.removed} entr${r.removed === 1 ? "y" : "ies"} deleted. The deletion itself is recorded as a new entry.`);
      setPage(0);
      await load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setPending(null);
    }
  };

  const total = data?.total ?? 0;
  const first = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const last = Math.min(total, (page + 1) * PAGE_SIZE);
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="space-y-6">
      <div className="rise">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Administration</p>
        <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">Audit trail</h1>
        <p className="text-sm text-muted">
          Who did what, and when, newest first. Entries cannot be edited, but you can delete them. For appraisal entries only the kind of change is shown, never what was written.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="audit-action" className="mb-1 block text-sm font-medium">Show</label>
          <select
            id="audit-action"
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              setPage(0);
            }}
            className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm"
          >
            <option value="">Everything</option>
            {AUDIT_FILTER_ACTIONS.map((a) => (
              <option key={a} value={a}>{auditActionLabel(a)}</option>
            ))}
          </select>
        </div>
        <div className="pb-0.5"><RefreshButton onRefresh={load} loadedAt={loadedAt} /></div>
        <p className="pb-2 text-sm text-muted" role="status">{data ? (total === 0 ? "No entries" : `Entries ${first}–${last} of ${total}`) : ""}</p>
        <Button variant="secondary" size="sm" disabled={!data || total === 0} onClick={() => setPending({})}>
          {action ? "Delete all of this kind" : "Delete all entries"}
        </Button>
      </div>
      {notice && <Alert tone="ok" title="Done">{notice}</Alert>}

      {error && <Alert tone="error" title="Could not load the audit trail" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>}

      {!data && !error ? (
        <div className="space-y-2" aria-busy="true" aria-label="Loading">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-56 w-full" />
        </div>
      ) : data && data.items.length === 0 ? (
        <EmptyState>{action ? "Nothing of that kind has been recorded." : "Nothing has been recorded yet."}</EmptyState>
      ) : (
        data && (
          <>
          <ul className="rise grid gap-3 sm:hidden" style={{ "--i": 2 } as React.CSSProperties} aria-label="Audit trail">
            {data.items.map((i) => (
              <li key={i.id} className="rounded-xl border border-line bg-surface p-4 shadow-[var(--shadow-paper)]">
                <div className="flex items-start justify-between gap-3">
                  <p className="font-display text-lg font-medium text-navy">{auditActionLabel(i.action)}</p>
                  <span className="shrink-0 text-right text-xs tabular-nums text-muted">{formatDateTime(i.at)}</span>
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-sm">
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Who</dt><dd className="mt-0.5 break-all">{i.actorEmail ?? <span className="text-muted">System</span>}</dd></div>
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">About</dt><dd className="mt-0.5">{i.entityType.toLowerCase().replace(/_/g, " ")}{i.entityId != null && <span className="tabular-nums"> #{i.entityId}</span>}</dd></div>
                  <div className="col-span-2"><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Details</dt><dd className="mt-0.5 text-muted">{describeDetails(i.details)}</dd></div>
                </dl>
                <Button variant="secondary" size="sm" className="mt-3 w-full" onClick={() => setPending({ id: i.id })}>Delete this entry</Button>
              </li>
            ))}
          </ul>
          <div className="rise hidden overflow-x-auto rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:block" style={{ "--i": 2 } as React.CSSProperties}>
            <table className="w-full min-w-[48rem] border-collapse text-left text-sm">
              <caption className="sr-only">Audit trail</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  {["When", "Who", "What", "About", "Details", ""].map((h, n) => (
                    <th key={n} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h || <span className="sr-only">Delete</span>}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((i) => (
                  <tr key={i.id} className="border-t border-line align-top">
                    <td className="whitespace-nowrap px-4 py-2.5 tabular-nums text-muted">{formatDateTime(i.at)}</td>
                    <td className="px-4 py-2.5">{i.actorEmail ?? <span className="text-muted">System</span>}</td>
                    <td className="px-4 py-2.5 font-medium">{auditActionLabel(i.action)}</td>
                    <td className="px-4 py-2.5 text-muted">
                      {i.entityType.toLowerCase().replace(/_/g, " ")}
                      {i.entityId != null && <span className="tabular-nums"> #{i.entityId}</span>}
                    </td>
                    <td className="px-4 py-2.5 text-xs text-muted">{describeDetails(i.details)}</td>
                    <td className="px-4 py-2.5 text-right">
                      <Button variant="secondary" size="sm" onClick={() => setPending({ id: i.id })}>Delete<span className="sr-only"> entry {i.id}</span></Button>
                    </td>
                  </tr>
                ))}
                </tbody>
              </table>
            </div>
          </>
        )
      )}

      {data && total > PAGE_SIZE && (
        <nav aria-label="Pages" className="flex items-center justify-between gap-3">
          <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>Newer</Button>
          <span className="text-sm text-muted">Page {page + 1} of {pages}</span>
          <Button variant="secondary" size="sm" disabled={page + 1 >= pages} onClick={() => setPage((p) => p + 1)}>Older</Button>
        </nav>
      )}

      <ConfirmDialog
        open={pending !== null}
        destructive
        title={pending?.id === undefined ? (action ? "Delete all entries of this kind?" : "Delete the whole audit trail?") : "Delete this entry?"}
        confirmLabel="Delete"
        message={<p>{pending?.id === undefined ? `This permanently removes ${total} entr${total === 1 ? "y" : "ies"} and cannot be undone.` : "This permanently removes the entry and cannot be undone."} One new entry will record that you did it.</p>}
        onConfirm={() => void remove()}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}
