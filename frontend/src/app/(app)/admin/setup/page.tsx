"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Card, EmptyState, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { YearReadinessDialog } from "@/components/admin/YearReadinessDialog";
import { Dialog } from "@/components/ui/Dialog";
import { ChoiceInput, FieldInput } from "@/components/ui/FieldInput";
import { ApiError, get, post, put } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import type { AcademicYear, AdminReference, Department, FieldMeta, PolicyVersion } from "@/lib/types";

const MAX_CRITERION = 100;

function meta(name: string, label: string, type: FieldMeta["type"], maxLength: number | null = null): FieldMeta {
  return { name, label, type, required: true, maxLength, min: null, max: null, scale: null, allowed: null };
}

function errorText(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong. Please try again.";
}

export default function SetupPage() {
  const [reference, setReference] = useState<AdminReference | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setReference(await get<AdminReference>("/api/admin/reference"));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void reload();
  }, [reload]);

  if (!reference) {
    return error ? (
      <Alert tone="error" title="Could not load" action={<Button variant="secondary" size="sm" onClick={() => void reload()}>Try again</Button>}>{error}</Alert>
    ) : (
      <div className="space-y-3" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-10 w-1/3" />
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-10">
      <div className="rise">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Administration</p>
        <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">Departments, years &amp; scoring</h1>
        <p className="text-sm text-muted">The reference data every appraisal is built on. Nothing here is deleted; things are closed, and policies are published as new versions.</p>
      </div>
      {error && <Alert tone="error" title="Problem">{error}</Alert>}
      {notice && <Alert tone="ok" title="Done">{notice}</Alert>}

      <Departments departments={reference.departments} onChanged={async (m) => { setNotice(m); await reload(); }} onError={setError} />
      <Years years={reference.academicYears} onChanged={async (m) => { setNotice(m); await reload(); }} onError={setError} />
      <Policy reference={reference} onNotice={setNotice} onError={setError} />
    </div>
  );
}

// ---------------------------------------------------------------- departments

function Departments({ departments, onChanged, onError }: { departments: Department[]; onChanged: (message: string) => Promise<void>; onError: (m: string | null) => void }) {
  const [dialog, setDialog] = useState<{ department?: Department } | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const toggle = async (d: Department) => {
    setBusyId(d.id);
    onError(null);
    try {
      await put(`/api/admin/departments/${d.id}`, { name: d.name, active: !d.active });
      await onChanged(d.active ? `${d.code} was closed to new accounts.` : `${d.code} was reopened.`);
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section aria-labelledby="dept-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <RuledHeading id="dept-h">Departments</RuledHeading>
        <Button size="sm" onClick={() => setDialog({})}>Add a department</Button>
      </div>
      <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
        <table className="w-full min-w-[34rem] border-collapse text-left text-sm">
          <caption className="sr-only">Departments</caption>
          <thead>
            <tr className="bg-brand-soft text-navy">
              <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Code</th>
              <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Name</th>
              <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Status</th>
              <th scope="col" className="px-4 py-2.5"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody>
            {departments.map((d) => (
              <tr key={d.id} className="border-t border-line">
                <td className="px-4 py-2.5 font-semibold tabular-nums">{d.code}</td>
                <td className="px-4 py-2.5">{d.name}</td>
                <td className="px-4 py-2.5">{d.active ? "Open" : <span className="text-muted">Closed</span>}</td>
                <td className="px-4 py-2.5">
                  <div className="flex justify-end gap-1.5">
                    <Button variant="secondary" size="sm" onClick={() => setDialog({ department: d })}>Rename<span className="sr-only"> {d.code}</span></Button>
                    <Button variant="secondary" size="sm" loading={busyId === d.id} onClick={() => void toggle(d)}>
                      {d.active ? "Close" : "Reopen"}<span className="sr-only"> {d.code}</span>
                    </Button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-muted">A closed department cannot be chosen for new accounts. People already in it, and their appraisals, are unaffected. A department&apos;s code never changes.</p>
      <DepartmentDialog
        open={dialog !== null}
        department={dialog?.department}
        onClose={() => setDialog(null)}
        onSaved={async (m) => {
          setDialog(null);
          await onChanged(m);
        }}
      />
    </section>
  );
}

function DepartmentDialog({ open, department, onClose, onSaved }: { open: boolean; department?: Department; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  return (
    <Dialog open={open} onClose={onClose} title={department ? `Rename ${department.code}` : "Add a department"}>
      <DepartmentForm department={department} onClose={onClose} onSaved={onSaved} />
    </Dialog>
  );
}

function DepartmentForm({ department, onClose, onSaved }: { department?: Department; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [code, setCode] = useState("");
  const [name, setName] = useState(department?.name ?? "");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      if (department) {
        await put(`/api/admin/departments/${department.id}`, { name });
        await onSaved(`${department.code} was renamed.`);
      } else {
        await post("/api/admin/departments", { code, name });
        await onSaved(`${code.trim().toUpperCase()} was added.`);
      }
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setErrors(err.fieldErrors);
      else setMessage(errorText(err));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {message && <Alert tone="error" title="Not saved">{message}</Alert>}
      {!department && (
        <FieldInput meta={meta("code", "Code", "TEXT", 16)} value={code} onChange={setCode} error={errors.code} hint="2 to 16 capital letters or digits, such as ISE. It cannot be changed later." autoFocus />
      )}
      <FieldInput meta={meta("name", "Name", "TEXT", 120)} value={name} onChange={setName} error={errors.name} autoFocus={!!department} />
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={busy}>{department ? "Save name" : "Add department"}</Button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- academic years

function Years({ years, onChanged, onError }: { years: AcademicYear[]; onChanged: (message: string) => Promise<void>; onError: (m: string | null) => void }) {
  const [open, setOpen] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  // The checklist is read before a year is closed or reopened; with act false it is only shown.
  const [checking, setChecking] = useState<{ year: AcademicYear; act: boolean } | null>(null);

  const toggle = async (y: AcademicYear) => {
    setBusyId(y.id);
    onError(null);
    try {
      await put(`/api/admin/academic-years/${y.id}`, { active: !y.active });
      await onChanged(y.active ? `${y.name} was closed: no new appraisals can start in it.` : `${y.name} was reopened.`);
    } catch (e) {
      onError(errorText(e));
    } finally {
      setBusyId(null);
    }
  };

  const latestOpen = years.find((y) => y.active);

  return (
    <section aria-labelledby="year-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <RuledHeading id="year-h">Academic years</RuledHeading>
        <Button size="sm" onClick={() => setOpen(true)}>Open a new year</Button>
      </div>
      {years.length === 0 ? (
        <EmptyState>No academic year exists. Open one so faculty can start their appraisals.</EmptyState>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
          <table className="w-full min-w-[34rem] border-collapse text-left text-sm">
            <caption className="sr-only">Academic years</caption>
            <thead>
              <tr className="bg-brand-soft text-navy">
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Year</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">From</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">To</th>
                <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Status</th>
                <th scope="col" className="px-4 py-2.5"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {years.map((y) => (
                <tr key={y.id} className="border-t border-line">
                  <td className="px-4 py-2.5 font-semibold tabular-nums">{y.name}</td>
                  <td className="px-4 py-2.5 tabular-nums">{formatDate(y.startDate)}</td>
                  <td className="px-4 py-2.5 tabular-nums">{formatDate(y.endDate)}</td>
                  <td className="px-4 py-2.5">
                    {y.active ? "Open" : <span className="text-muted">Closed</span>}
                    {latestOpen?.id === y.id && <span className="ml-2 rounded-[3px] bg-warm px-1.5 py-0.5 text-[11px] font-bold uppercase tracking-[0.08em] text-warn">New appraisals start here</span>}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    <span className="inline-flex gap-1">
                      <Button variant="ghost" size="sm" onClick={() => setChecking({ year: y, act: false })}>Checklist<span className="sr-only"> for {y.name}</span></Button>
                      <Button variant="secondary" size="sm" loading={busyId === y.id} onClick={() => setChecking({ year: y, act: true })}>
                        {y.active ? "Close" : "Reopen"}<span className="sr-only"> {y.name}</span>
                      </Button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-xs text-muted">New appraisals start in the open year that begins latest. Closing a year does not touch appraisals already started in it.</p>
      {checking && (
        <YearReadinessDialog
          open
          yearId={checking.year.id}
          yearName={checking.year.name}
          confirmLabel={checking.act ? (checking.year.active ? `Close ${checking.year.name}` : `Reopen ${checking.year.name}`) : null}
          onClose={() => setChecking(null)}
          onConfirm={() => {
            const y = checking.year;
            setChecking(null);
            void toggle(y);
          }}
        />
      )}
      <YearDialog
        open={open}
        onClose={() => setOpen(false)}
        onSaved={async (m) => {
          setOpen(false);
          await onChanged(m);
        }}
      />
    </section>
  );
}

function YearDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  return (
    <Dialog open={open} onClose={onClose} title="Open a new academic year">
      <YearForm onClose={onClose} onSaved={onSaved} />
    </Dialog>
  );
}

function YearForm({ onClose, onSaved }: { onClose: () => void; onSaved: (message: string) => Promise<void> }) {
  const [name, setName] = useState("");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setMessage(null);
    try {
      await post("/api/admin/academic-years", { name: name.trim(), startDate: start, endDate: end });
      await onSaved(`${name.trim()} was opened, with each cadre's latest scoring policy copied into it.`);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setErrors(err.fieldErrors);
      else setMessage(errorText(err));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {message && <Alert tone="error" title="Not saved">{message}</Alert>}
      <FieldInput meta={meta("name", "Name", "TEXT", 16)} value={name} onChange={setName} error={errors.name} hint="Like 2027-28: the second part is the following year." autoFocus />
      <div className="grid gap-4 sm:grid-cols-2">
        <FieldInput meta={meta("startDate", "Starts on", "DATE")} value={start} onChange={setStart} error={errors.startDate} />
        <FieldInput meta={meta("endDate", "Ends on", "DATE")} value={end} onChange={setEnd} error={errors.endDate} />
      </div>
      <p className="text-sm text-muted">So faculty can start at once, each cadre&apos;s latest scoring policy from the previous year is copied in. You can then publish changes to the marks.</p>
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={busy}>Open year</Button>
      </div>
    </form>
  );
}

// ---------------------------------------------------------------- scoring policy

function Policy({ reference, onNotice, onError }: { reference: AdminReference; onNotice: (m: string | null) => void; onError: (m: string | null) => void }) {
  const [yearId, setYearId] = useState<number | null>(reference.academicYears.find((y) => y.active)?.id ?? reference.academicYears[0]?.id ?? null);
  const [versions, setVersions] = useState<PolicyVersion[] | null>(null);
  const [editing, setEditing] = useState<PolicyVersion | null>(null);

  const load = useCallback(async () => {
    if (yearId === null) return;
    try {
      setVersions(await get<PolicyVersion[]>(`/api/admin/policies?academicYearId=${yearId}`));
    } catch (e) {
      onError(errorText(e));
    }
  }, [yearId, onError]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reload when the year changes
    setVersions(null);
    void load();
  }, [load]);

  // The newest version of each cadre is the one new appraisals use.
  const latest = useMemo(() => {
    const byCadre = new Map<number, PolicyVersion>();
    for (const v of versions ?? []) {
      const seen = byCadre.get(v.cadreId);
      if (!seen || v.version > seen.version) byCadre.set(v.cadreId, v);
    }
    return reference.cadres.filter((c) => c.active).map((c) => ({ cadre: c, policy: byCadre.get(c.id) ?? null, count: (versions ?? []).filter((v) => v.cadreId === c.id).length }));
  }, [versions, reference.cadres]);

  return (
    <section aria-labelledby="policy-h" className="space-y-3">
      <RuledHeading id="policy-h">Scoring policy</RuledHeading>
      <p className="text-sm text-muted">
        The maximum marks for each criterion, per cadre. Only B1 to B4 have a maximum; B5 to B9 are marked per entry, the same for every cadre, with no upper limit. Publishing creates a new version: appraisals already started keep the
        marks they began with, and appraisals started from now on use the new ones. The first five criteria also carry the scoring components of the college&apos;s cadre-wise
        document (what each maximum is made of); faculty see them on the score sheet and in the report.
      </p>

      {reference.academicYears.length === 0 ? (
        <EmptyState>Open an academic year first.</EmptyState>
      ) : (
        <>
          <div className="max-w-xs">
            <ChoiceInput
              label="Academic year"
              required
              value={yearId === null ? "" : String(yearId)}
              onChange={(v) => setYearId(v ? Number(v) : null)}
              options={reference.academicYears.map((y) => ({ value: String(y.id), label: `${y.name}${y.active ? "" : " (closed)"}` }))}
              placeholder="Choose a year"
            />
          </div>

          {!versions ? (
            <Skeleton className="h-64 w-full" />
          ) : versions.length === 0 ? (
            <Alert tone="warn" title="No scoring policy for this year">
              Faculty cannot start an appraisal in this year until each cadre has a policy. Use Publish marks below.
            </Alert>
          ) : null}

          {versions && (
            <Card className="overflow-x-auto">
              <table className="w-full min-w-[44rem] border-collapse text-left text-sm">
                <caption className="sr-only">Maximum marks per criterion and cadre</caption>
                <thead>
                  <tr className="bg-brand-soft text-navy">
                    <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Criterion</th>
                    {latest.map(({ cadre, policy, count }) => (
                      <th key={cadre.id} scope="col" className="px-3 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">
                        {cadre.name}
                        <span className="block font-normal normal-case tracking-normal text-muted">
                          {policy ? `Version ${policy.version}${count > 1 ? ` of ${count}` : ""}` : "No policy"}
                        </span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {reference.criteria.filter((c) => !c.perEntry).map((c) => (
                    <tr key={c.code} className="border-t border-line">
                      <th scope="row" className="px-4 py-2 text-left font-normal">{c.label}</th>
                      {latest.map(({ cadre, policy }) => (
                        <td key={cadre.id} className="px-3 py-2 text-right tabular-nums">{policy ? (policy.marks[c.code] ?? "—") : "—"}</td>
                      ))}
                    </tr>
                  ))}
                  <tr className="border-t-2 border-navy bg-canvas font-semibold">
                    <th scope="row" className="px-4 py-2 text-left">Total of B1 to B4</th>
                    {latest.map(({ cadre, policy }) => (
                      <td key={cadre.id} className="px-3 py-2 text-right tabular-nums">{policy ? policy.total : "—"}</td>
                    ))}
                  </tr>
                  <tr className="border-t border-line">
                    <td className="px-4 py-2" />
                    {latest.map(({ cadre, policy }) => (
                      <td key={cadre.id} className="px-3 py-2 text-right">
                        <Button
                          variant="secondary"
                          size="sm"
                          onClick={() => setEditing(policy ?? blankPolicy(yearId!, cadre.id, cadre.name, reference))}
                        >
                          {policy ? "Change marks" : "Publish marks"}<span className="sr-only"> for {cadre.name}</span>
                        </Button>
                      </td>
                    ))}
                  </tr>
                </tbody>
              </table>
            </Card>
          )}
          {versions && versions.length > 0 && (
            <p className="text-xs text-muted">
              Latest published {formatDateTime(versions.reduce((a, b) => (a.createdAt > b.createdAt ? a : b)).createdAt)}.
            </p>
          )}
        </>
      )}

      <PolicyDialog
        policy={editing}
        reference={reference}
        onClose={() => setEditing(null)}
        onPublished={async (v) => {
          setEditing(null);
          onNotice(`Version ${v.version} of the ${v.cadre} policy was published. It applies to appraisals started from now on.`);
          await load();
        }}
      />
    </section>
  );
}

function blankPolicy(yearId: number, cadreId: number, cadre: string, reference: AdminReference): PolicyVersion {
  return { id: 0, academicYearId: yearId, cadreId, cadre, version: 0, active: true, createdAt: "", marks: Object.fromEntries(reference.criteria.map((c) => [c.code, 0])), total: 0, components: {} };
}

function PolicyDialog({ policy, reference, onClose, onPublished }: { policy: PolicyVersion | null; reference: AdminReference; onClose: () => void; onPublished: (v: PolicyVersion) => Promise<void> }) {
  return (
    <Dialog open={policy !== null} onClose={onClose} title={policy ? `${policy.cadre}: maximum marks` : ""} wide>
      {policy && <PolicyForm policy={policy} reference={reference} onClose={onClose} onPublished={onPublished} />}
    </Dialog>
  );
}

function PolicyForm({ policy, reference, onClose, onPublished }: { policy: PolicyVersion; reference: AdminReference; onClose: () => void; onPublished: (v: PolicyVersion) => Promise<void> }) {
  const fixed = reference.criteria.filter((c) => !c.perEntry);
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(fixed.map((c) => [c.code, String(policy.marks[c.code] ?? 0)])));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const numbers = fixed.map((c) => (/^\d{1,3}$/.test(values[c.code].trim()) ? Number(values[c.code].trim()) : NaN));
  const valid = numbers.every((n) => Number.isInteger(n));
  const total = numbers.reduce((a, n) => a + (Number.isInteger(n) ? n : 0), 0);
  // A criterion's scoring components add up to its maximum, so a changed maximum is published without them.
  const losingBreakdown = fixed.filter((c, i) => (policy.components[c.code]?.length ?? 0) > 0 && Number.isInteger(numbers[i]) && numbers[i] !== policy.marks[c.code]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!valid) return;
    setBusy(true);
    setMessage(null);
    setErrors({});
    try {
      const marks = Object.fromEntries(fixed.map((c, i) => [c.code, numbers[i]]));
      const v = await post<PolicyVersion>("/api/admin/policies", { academicYearId: policy.academicYearId, cadreId: policy.cadreId, marks });
      await onPublished(v);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) {
        setErrors(err.fieldErrors);
        setMessage(err.fieldErrors.marks ?? "Some marks need attention.");
      } else setMessage(errorText(err));
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {message && <Alert tone="error" title="Not published">{message}</Alert>}
      <p className="text-sm text-muted">
        Whole numbers. A criterion that does not apply to this cadre is 0. B5 to B9 are marked per entry and have no maximum, so they are not listed.
        {policy.version > 0 ? ` This publishes version ${policy.version + 1}.` : " This publishes version 1."}
      </p>
      <div className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
        {fixed.map((c) => {
          const raw = values[c.code];
          const bad = !/^\d{1,3}$/.test(raw.trim());
          const parts = policy.components[c.code] ?? [];
          return (
            <div key={c.code}>
              <FieldInput
                meta={{ name: c.code, label: c.label, type: "INT", required: true, maxLength: null, min: 0, max: MAX_CRITERION, scale: null, allowed: null }}
                value={raw}
                onChange={(v) => setValues((s) => ({ ...s, [c.code]: v }))}
                error={errors[`marks.${c.code}`] ?? (bad ? "Enter a whole number." : undefined)}
              />
              {parts.length > 0 && (
                <p className="mt-1 text-xs text-muted">Made of: {parts.map((p) => `${p.description} – ${p.maxMarks}`).join("; ")}.</p>
              )}
            </div>
          );
        })}
      </div>
      <div className="flex items-center justify-between rounded-md border border-line bg-canvas px-4 py-2 text-sm font-semibold" role="status">
        <span>Total of B1 to B4</span>
        <span className="font-display text-xl tabular-nums">{valid ? total : "—"}</span>
      </div>
      {losingBreakdown.length > 0 && (
        <Alert tone="warn" title="The scoring components will not be carried over">
          The components of {losingBreakdown.map((c) => c.label).join("; ")} add up to the present maximum, not the new one. The new version will show {losingBreakdown.length === 1 ? "that criterion" : "those criteria"} with
          its maximum only. Appraisals already started keep their components.
        </Alert>
      )}
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={busy} disabled={!valid}>Publish new version</Button>
      </div>
    </form>
  );
}
