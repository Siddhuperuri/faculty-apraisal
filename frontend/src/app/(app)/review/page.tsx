"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { ReviewerTabs } from "@/components/console/parts";
import { usePdfZip } from "@/components/console/PdfZip";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { RowCheckbox, SelectAllCheckbox, SelectionToolbar } from "@/components/ui/Selection";
import { NOTHING, checkState, hiddenCount, keepOnly, toggle, toggleShown, type Selection } from "@/lib/selection";
import { Alert, Badge, Button, EmptyState, Skeleton, StatusBadge, linkButton } from "@/components/ui/primitives";
import { get, postDownload, saveFile } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { STATUS_INFO } from "@/lib/labels";
import { LEVELS, type ReviewerRole } from "@/lib/hierarchy";
import type { ListItem } from "@/lib/types";

export default function ReviewQueue() {
  const { user } = useAuth();
  const role = user?.role as ReviewerRole;
  const [items, setItems] = useState<ListItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<string>("");
  const [query, setQuery] = useState("");
  // The department progress cards on the console link here with ?department=Name.
  const [department, setDepartment] = useState(useSearchParams().get("department") ?? "");
  const [selected, setSelected] = useState<Selection>(NOTHING);
  const [loadedAt, stamp] = useLoadedAt();
  const [sheetBusy, setSheetBusy] = useState<string | null>(null);
  const [sheetNotice, setSheetNotice] = useState<{ tone: "ok" | "error"; text: string } | null>(null);
  const pdfs = usePdfZip(`/api/${role === "HOD" ? "hod" : role === "DIRECTOR" ? "director" : "principal"}/appraisals/pdfs`);

  const load = useCallback(async () => {
    try {
      const list = await get<ListItem[]>("/api/appraisals");
      setItems(list);
      setSelected((sel) => keepOnly(sel, list.map((i) => i.id)));
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

  // The statuses that are waiting for this level to act.
  const actionable = useMemo(() => LEVELS[role]?.actionable ?? [], [role]);
  // An appraisal with a query raised is with the HoD but waiting for the faculty member, so it is not waiting for the HoD.
  const needsAction = useCallback((i: ListItem) => actionable.includes(i.status) && !i.queryRaised, [actionable]);

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (items ?? [])
      .filter((i) => (status ? i.status === status : true))
      .filter((i) => (department ? i.department === department : true))
      .filter((i) => (q ? `${i.facultyName} ${i.employeeId} ${i.department}`.toLowerCase().includes(q) : true))
      .sort((a, b) => Number(needsAction(b)) - Number(needsAction(a)));
  }, [items, status, department, query, needsAction]);

  const shownIds = shown.map((i) => i.id);
  // The spreadsheet and CSV export is the HoD's own (the Principal and the Director Technical have no such file).
  const downloadSheet = async (format: "xlsx" | "csv", which: "selected" | "shown") => {
    const ids = which === "selected"
      ? (items ?? []).filter((i) => selected.has(i.id)).map((i) => i.facultyId)
      : shown.map((i) => i.facultyId);
    setSheetBusy(`${which}-${format}`);
    setSheetNotice(null);
    try {
      const file = await postDownload("/api/hod/appraisals/export", { facultyIds: [...new Set(ids)], format });
      saveFile(file.blob, file.fileName);
      setSheetNotice({ tone: "ok", text: `${file.fileName} was downloaded (${new Set(ids).size} faculty).` });
    } catch (e) {
      setSheetNotice({ tone: "error", text: (e as Error).message });
    } finally {
      setSheetBusy(null);
    }
  };
  const busy = pdfs.busy !== null || sheetBusy !== null;
  const headerState = checkState(selected, shownIds);
  const hiddenSelected = hiddenCount(selected, shownIds);

  if (error) {
    return <Alert tone="error" title="Could not load the review queue" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!items) return <div className="space-y-3" aria-busy="true"><Skeleton className="h-8 w-1/3" /><Skeleton className="h-48 w-full" /></div>;

  const waiting = items.filter(needsAction).length;
  const awaitingFaculty = items.filter((i) => i.queryRaised).length;
  const statuses = Array.from(new Set(items.map((i) => i.status)));
  const departments = Array.from(new Set([...items.map((i) => i.department), ...(department ? [department] : [])])).sort();

  return (
    <div className="space-y-6">
      <ReviewerTabs role={role} />
      <div className="rise flex flex-wrap items-end justify-between gap-4">
       <div>
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">{LEVELS[role].title}</p>
        <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">{role === "HOD" ? "Department review" : `${LEVELS[role].title} review`}</h1>
        <p className="text-sm text-muted">
          {waiting === 0 ? "Nothing is waiting for you right now." : `${waiting} appraisal${waiting === 1 ? " is" : "s are"} waiting for your action.`}
          {awaitingFaculty > 0 && ` ${awaitingFaculty} ${awaitingFaculty === 1 ? "is" : "are"} waiting for the faculty member to meet you.`}
        </p>
       </div>
       <RefreshButton onRefresh={load} loadedAt={loadedAt} />
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="search" className="mb-1 block text-sm font-medium">Search</label>
          <input id="search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name, employee ID or department" className="w-72 max-w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
        </div>
        <div>
          <label htmlFor="status" className="mb-1 block text-sm font-medium">Status</label>
          <select id="status" value={status} onChange={(e) => setStatus(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
            <option value="">All statuses</option>
            {statuses.map((s) => <option key={s} value={s}>{STATUS_INFO[s].label}</option>)}
          </select>
        </div>
        {(departments.length > 1 || department) && (
          <div>
            <label htmlFor="department" className="mb-1 block text-sm font-medium">Department</label>
            <select id="department" value={department} onChange={(e) => setDepartment(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
              <option value="">All departments</option>
              {departments.map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
        )}
      </div>

      <SelectionToolbar selection={selected} hidden={hiddenSelected} singular="appraisal" plural="appraisals" onClear={() => setSelected(NOTHING)}>
        {role === "HOD" && (
          <>
            <Button size="sm" loading={sheetBusy === "selected-xlsx"} disabled={busy} onClick={() => void downloadSheet("xlsx", "selected")}>
              Download Excel (.xlsx)
            </Button>
            <Button variant="secondary" size="sm" loading={sheetBusy === "selected-csv"} disabled={busy} onClick={() => void downloadSheet("csv", "selected")}>
              Download CSV
            </Button>
          </>
        )}
        <Button variant={role === "HOD" ? "secondary" : "primary"} size="sm" loading={pdfs.busy === "selected"} disabled={busy}
          onClick={() => void pdfs.download({ appraisalIds: [...selected] })}>
          Download PDFs (zip)
        </Button>
      </SelectionToolbar>
      {items.length > 0 && (
        <div className="flex flex-wrap items-center gap-3">
          {role === "HOD" && (
            <>
              <Button variant="secondary" size="sm" loading={sheetBusy === "shown-xlsx"} disabled={busy} onClick={() => void downloadSheet("xlsx", "shown")}>
                Download all {shown.length} shown (.xlsx)
              </Button>
              <Button variant="secondary" size="sm" loading={sheetBusy === "shown-csv"} disabled={busy} onClick={() => void downloadSheet("csv", "shown")}>
                Download all {shown.length} shown (CSV)
              </Button>
            </>
          )}
          <Button variant="secondary" size="sm" loading={pdfs.busy === "all"} disabled={busy}
            onClick={() => void pdfs.download({ appraisalIds: shownIds })}>
            Download all {shown.length} shown as PDFs (zip)
          </Button>
          <p className="text-xs text-muted">Everything this list shows; narrow the list first to download a group, or tick rows to download only those.</p>
        </div>
      )}
      {sheetNotice && (
        <Alert tone={sheetNotice.tone === "ok" ? "ok" : "error"} title={sheetNotice.tone === "ok" ? "Downloaded" : "Could not export"}>
          {sheetNotice.text}
        </Alert>
      )}
      {pdfs.notice && (
        <Alert tone={pdfs.notice.tone === "ok" ? "ok" : "error"} title={pdfs.notice.tone === "ok" ? "Downloaded" : "Could not download"}>
          {pdfs.notice.text}
        </Alert>
      )}

      {shown.length === 0 ? (
        <EmptyState action={items.length > 0 ? <Button variant="secondary" onClick={() => { setQuery(""); setStatus(""); setDepartment(""); }}>Clear filters</Button> : undefined}>
          {items.length === 0 ? "No appraisals have been submitted to you yet." : "No appraisals match your search."}
        </EmptyState>
      ) : (
        <>
        <ul className="rise grid gap-3 sm:hidden" style={{ "--i": 2 } as React.CSSProperties} aria-label="Appraisals for review">
          {shown.map((i) => (
            <li key={i.id} className={`rounded-xl border bg-surface p-4 shadow-[var(--shadow-paper)] ${selected.has(i.id) ? "border-brand bg-brand-soft/40" : "border-line"}`}>
              <div className="flex items-start justify-between gap-3">
                <div className="flex min-w-0 items-start gap-3">
                  <RowCheckbox checked={selected.has(i.id)} onChange={() => setSelected(toggle(selected, i.id))} label={`Select ${i.facultyName}`} />
                  <div className="min-w-0">
                    <p className="font-display text-lg font-medium leading-snug text-navy">{i.facultyName}</p>
                    <p className="text-xs text-muted">{i.employeeId}</p>
                  </div>
                </div>
                <StatusBadge status={i.status} queryRaised={i.queryRaised} />
              </div>
              {needsAction(i) && <p className="mt-3"><Badge tone="warn">Needs your action</Badge></p>}
              {i.queryRaised && <p className="mt-3"><Badge>Awaiting the faculty member</Badge></p>}
              <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-sm">
                <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Department</dt><dd className="mt-0.5">{i.department}</dd></div>
                <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Year</dt><dd className="mt-0.5 tabular-nums">{i.academicYear}</dd></div>
                <div className="col-span-2"><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Submitted</dt><dd className="mt-0.5">{formatDateTime(i.submittedAt)}</dd></div>
              </dl>
              <Link href={`/appraisals/${i.id}`} className={`${linkButton("primary")} mt-4 w-full`}>Open appraisal<span className="sr-only"> for {i.facultyName}</span></Link>
            </li>
          ))}
        </ul>
        <div className="rise hidden overflow-x-auto rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:block" style={{ "--i": 2 } as React.CSSProperties}>
          <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
            <caption className="sr-only">Appraisals for review</caption>
            <thead>
              <tr className="bg-brand-soft text-navy">
                <th scope="col" className="w-10 px-4 py-2.5">
                  <SelectAllCheckbox state={headerState} onChange={() => setSelected(toggleShown(selected, shownIds))} label={`Select all ${shown.length} appraisals shown`} />
                </th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Faculty</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Department</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Year</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Status</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Submitted</th>
                <th scope="col" className="px-4 py-2"><span className="sr-only">Open</span></th>
              </tr>
            </thead>
            <tbody>
              {shown.map((i) => (
                <tr key={i.id} aria-selected={selected.has(i.id)} className={`border-t border-line transition-colors hover:bg-warm/40 ${selected.has(i.id) ? "bg-brand-soft/50" : ""}`}>
                  <td className="px-4 py-2.5">
                    <RowCheckbox checked={selected.has(i.id)} onChange={() => setSelected(toggle(selected, i.id))} label={`Select ${i.facultyName}`} />
                  </td>
                  <td className="px-4 py-2.5">
                    <span className="font-display text-base font-medium">{i.facultyName}</span>
                    <span className="block text-xs text-muted">{i.employeeId}</span>
                  </td>
                  <td className="px-4 py-2.5">{i.department}</td>
                  <td className="px-4 py-2.5 tabular-nums">{i.academicYear}</td>
                  <td className="px-4 py-2.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <StatusBadge status={i.status} queryRaised={i.queryRaised} />
                      {needsAction(i) && <Badge tone="warn">Needs your action</Badge>}
                      {i.queryRaised && <Badge>Awaiting the faculty member</Badge>}
                    </div>
                  </td>
                  <td className="px-4 py-2.5 text-muted">{formatDateTime(i.submittedAt)}</td>
                  <td className="px-4 py-2.5 text-right">
                    <Link href={`/appraisals/${i.id}`} className="font-medium text-brand hover:underline">Open<span className="sr-only"> {i.facultyName}</span></Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        </>
      )}
    </div>
  );
}
