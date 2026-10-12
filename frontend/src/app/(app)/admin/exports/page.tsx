"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Badge, Button, EmptyState, Skeleton, StatusBadge } from "@/components/ui/primitives";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { RowCheckbox, SelectAllCheckbox, SelectionToolbar } from "@/components/ui/Selection";
import { get, postDownload, saveFile } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { NOTHING, checkState, hiddenCount, toggle, toggleShown, type Selection } from "@/lib/selection";
import type { AdminReference, ExportRoster, ExportRow } from "@/lib/types";

const STATUSES: { value: string; label: string }[] = [
  { value: "NOT_SUBMITTED", label: "Not submitted" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "HOD_REVIEW", label: "With the HoD" },
  { value: "HOD_APPROVED", label: "HoD recommended" },
  { value: "PRINCIPAL_REVIEW", label: "With the Principal / Director" },
  { value: "APPROVED", label: "Approved" },
];

/**
 * Appraisal details of chosen faculty as a spreadsheet or CSV file, for the administrator: choose the year, then narrow by
 * department, designation and where the appraisal stands, tick the faculty, and download. The server checks every id again.
 */
export default function AppraisalExportPage() {
  const [reference, setReference] = useState<AdminReference | null>(null);
  const [roster, setRoster] = useState<ExportRoster | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [yearId, setYearId] = useState("");
  const [department, setDepartment] = useState("");
  const [cadre, setCadre] = useState("");
  const [status, setStatus] = useState("");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<Selection>(NOTHING);
  const [busy, setBusy] = useState<"xlsx" | "csv" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (yearId) params.set("academicYearId", yearId);
    if (department) params.set("departmentId", department);
    if (cadre) params.set("cadreId", cadre);
    if (status) params.set("status", status);
    try {
      setRoster(await get<ExportRoster>(`/api/admin/appraisals/roster?${params}`));
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [yearId, department, cadre, status, stamp]);

  useEffect(() => {
    get<AdminReference>("/api/admin/reference").then(setReference).catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load when a filter changes
    void load();
  }, [load]);

  const shown: ExportRow[] = useMemo(() => {
    const q = query.trim().toLowerCase();
    const rows = roster?.rows ?? [];
    return q ? rows.filter((r) => `${r.name} ${r.employeeId} ${r.department} ${r.cadre}`.toLowerCase().includes(q)) : rows;
  }, [roster, query]);
  const shownIds = useMemo(() => shown.map((r) => r.facultyId), [shown]);
  const headerState = checkState(selected, shownIds);
  const hidden = hiddenCount(selected, shownIds);
  const narrowed = department !== "" || cadre !== "" || status !== "" || query.trim() !== "";

  const download = async (format: "xlsx" | "csv") => {
    setBusy(format);
    setNotice(null);
    setError(null);
    try {
      const file = await postDownload("/api/admin/appraisals/export", {
        facultyIds: [...selected],
        academicYearId: roster?.year?.id,
        format,
      });
      saveFile(file.blob, file.fileName);
      setNotice(`${file.fileName} was downloaded (${selected.size} faculty).`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  if (!reference || !roster) {
    return error ? (
      <Alert tone="error" title="Could not load the faculty list" action={<Button variant="secondary" size="sm" onClick={() => window.location.reload()}>Reload</Button>}>{error}</Alert>
    ) : (
      <div className="space-y-3" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-10 w-1/3" />
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  const year = roster.year;
  return (
    <div className="space-y-6">
      <div className="rise flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Administration</p>
          <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">Appraisal export</h1>
          <p className="max-w-2xl text-sm text-muted">
            Choose faculty and download their appraisal details for {year?.name ?? "the year"} as an Excel workbook or a CSV file. A
            faculty member who has not submitted is listed as &quot;Not submitted&quot;; a draft is private to its author and is never exported.
          </p>
        </div>
        <RefreshButton onRefresh={load} loadedAt={loadedAt} />
      </div>

      {error && <Alert tone="error" title="Problem">{error}</Alert>}
      {notice && <Alert tone="ok" title="Downloaded">{notice}</Alert>}

      <div role="search" aria-label="Filter faculty" className="space-y-3 rounded-xl border border-line bg-surface/70 p-3 sm:p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[14rem] flex-1">
            <label htmlFor="export-search" className="mb-1 block text-sm font-medium">Search</label>
            <input id="export-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name, employee ID or department" className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
          </div>
          <Pick id="export-year" label="Academic year" value={yearId || String(year?.id ?? "")} onChange={setYearId} all={null}
            options={roster.years.map((y) => ({ value: String(y.id), label: y.name }))} />
          <Pick id="export-dept" label="Department" value={department} onChange={setDepartment} all="All departments"
            options={reference.departments.map((d) => ({ value: String(d.id), label: d.code }))} />
          <Pick id="export-cadre" label="Designation" value={cadre} onChange={setCadre} all="All designations"
            options={reference.cadres.map((c) => ({ value: String(c.id), label: c.name }))} />
          <Pick id="export-status" label="Appraisal status" value={status} onChange={setStatus} all="Any status" options={STATUSES} />
        </div>
        <p className="text-sm text-muted" role="status">
          {narrowed ? `Showing ${shown.length} of ${roster.rows.length}` : roster.rows.length} faculty
          {roster.rows.length >= 5000 ? " (the first 5000; narrow the list)" : ""}
        </p>
      </div>

      <SelectionToolbar selection={selected} hidden={hidden} singular="faculty" plural="faculty" onClear={() => setSelected(NOTHING)}>
        <Button size="sm" loading={busy === "xlsx"} disabled={busy !== null} onClick={() => void download("xlsx")}>Download Excel (.xlsx)</Button>
        <Button variant="secondary" size="sm" loading={busy === "csv"} disabled={busy !== null} onClick={() => void download("csv")}>Download CSV</Button>
      </SelectionToolbar>

      {shown.length === 0 ? (
        <EmptyState>{narrowed ? "No faculty match these filters." : "There are no faculty yet."}</EmptyState>
      ) : (
        <>
          <label className="flex items-center gap-2 text-sm font-medium sm:hidden">
            <SelectAllCheckbox state={headerState} onChange={() => setSelected(toggleShown(selected, shownIds))} label={`Select all ${shown.length} faculty shown`} />
            Select all {shown.length} shown
          </label>
          <ul className="grid gap-3 sm:hidden" aria-label="Faculty">
            {shown.map((r) => (
              <li key={r.facultyId} className={`rounded-xl border bg-surface p-4 shadow-[var(--shadow-paper)] ${selected.has(r.facultyId) ? "border-brand bg-brand-soft/40" : "border-line"}`}>
                <div className="flex items-start gap-3">
                  <RowCheckbox checked={selected.has(r.facultyId)} onChange={() => setSelected(toggle(selected, r.facultyId))} label={`Select ${r.name}`} />
                  <div className="min-w-0">
                    <p className="font-display text-lg font-medium leading-snug text-navy">{r.name}</p>
                    <p className="text-xs text-muted">{r.employeeId} · {r.department} · {r.cadre}</p>
                    <p className="mt-2">{r.status ? <StatusBadge status={r.status} /> : <Badge>Not submitted</Badge>}</p>
                  </div>
                </div>
              </li>
            ))}
          </ul>
          <div className="hidden overflow-x-auto rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:block">
            <table className="w-full min-w-[46rem] border-collapse text-left text-sm">
              <caption className="sr-only">Faculty whose appraisal details can be exported</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  <th scope="col" className="w-10 px-4 py-2.5">
                    <SelectAllCheckbox state={headerState} onChange={() => setSelected(toggleShown(selected, shownIds))} label={`Select all ${shown.length} faculty shown`} />
                  </th>
                  {["Faculty", "Department", "Designation", "Appraisal", "Submitted"].map((h) => (
                    <th key={h} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={r.facultyId} aria-selected={selected.has(r.facultyId)} className={`border-t border-line transition-colors hover:bg-warm/40 ${selected.has(r.facultyId) ? "bg-brand-soft/50" : ""}`}>
                    <td className="px-4 py-2.5">
                      <RowCheckbox checked={selected.has(r.facultyId)} onChange={() => setSelected(toggle(selected, r.facultyId))} label={`Select ${r.name}`} />
                    </td>
                    <td className="px-4 py-2.5">
                      <span className="font-display text-base font-medium">{r.name}</span>
                      <span className="block text-xs text-muted">{r.employeeId}</span>
                    </td>
                    <td className="px-4 py-2.5">{r.department}</td>
                    <td className="px-4 py-2.5">{r.cadre}</td>
                    <td className="px-4 py-2.5">{r.status ? <StatusBadge status={r.status} /> : <span className="text-muted">Not submitted</span>}</td>
                    <td className="px-4 py-2.5 text-muted">{r.submittedAt ? formatDateTime(r.submittedAt) : "—"}</td>
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

function Pick({ id, label, value, onChange, all, options }: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  /** The wording of the "no filter" choice; null where every choice is a real one. */
  all: string | null;
  options: { value: string; label: string }[];
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium">{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
        {all !== null && <option value="">{all}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}
