"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, EmptyState, Skeleton } from "@/components/ui/primitives";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { formatCell } from "@/lib/format";
import type { Column, SectionUi } from "@/lib/formStructure";
import type { FieldMeta, Rec, SectionMeta } from "@/lib/types";
import { toApiRecord, toFormValues, validate, serverErrorsFor, type FieldErrors, type FormValues } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
import { RecordFields } from "./RecordFields";
import { SummaryStrip } from "./SummaryStrip";

type DialogState = { mode: "add" | "edit" | "duplicate"; record?: Rec } | null;

/**
 * A repeatable list (add, edit, duplicate, delete any number of rows). Desktop shows a table like the paper form;
 * phones show one card per record. Every change is saved straight away as a whole-list save.
 */
export function RecordsSection({ ui }: { ui: SectionUi }) {
  const { section, loadSection, metas, appraisal, saveSection } = useAppraisal();
  const [dialog, setDialog] = useState<DialogState>(null);
  const [deleting, setDeleting] = useState<Rec | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  useEffect(() => loadSection(ui.key), [loadSection, ui.key]);

  const st = section(ui.key);
  const meta = metas?.[ui.key];
  const editable = appraisal?.editable ?? false;

  const records = useMemo(() => {
    const all = st.data?.records ?? [];
    return ui.scope ? all.filter((r) => r[ui.scope!.field] === ui.scope!.value) : all;
  }, [st.data, ui.scope]);

  const columns = useMemo(() => (meta ? resolveColumns(meta, ui) : []), [meta, ui]);

  // Filters: a search box over everything the table shows, and a drop-down for each field whose records differ.
  const [query, setQuery] = useState("");
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const filterFields = useMemo(() => (meta ? resolveFilters(meta, ui, records) : []), [meta, ui, records]);
  const q = query.trim().toLowerCase();
  const active = q !== "" || filterFields.some((f) => chosen[f.name]);
  const shown = useMemo(
    () =>
      records.filter(
        (r) =>
          filterFields.every((f) => !chosen[f.name] || String(r[f.name] ?? "") === chosen[f.name]) &&
          (q === "" || columns.some((c) => c.cell(r).toLowerCase().includes(q))),
      ),
    [records, filterFields, chosen, q, columns],
  );
  // Worth showing when there is a choice to make: a drop-down that separates the records, or a list long enough to search.
  const showBar = records.length >= 2 && (filterFields.length > 0 || records.length >= 4);
  const clearFilters = () => {
    setQuery("");
    setChosen({});
  };

  // Lists such as the courses are shown as one table per group (per semester), each numbered from 1.
  const groups = useMemo(() => {
    const by = ui.groupBy;
    if (!by) return [{ key: "all", title: null as string | null, rows: shown }];
    const buckets = new Map<string, Rec[]>();
    for (const r of shown) {
      const k = String(r[by.field] ?? "");
      buckets.set(k, [...(buckets.get(k) ?? []), r]);
    }
    return [...buckets.entries()]
      .sort(([a], [b]) => Number(a) - Number(b) || a.localeCompare(b))
      .map(([k, rows]) => ({ key: k, title: by.title(k, rows.length) as string | null, rows }));
  }, [shown, ui.groupBy]);

  if (st.error) {
    return (
      <Alert tone="error" title="Could not load this section" action={<Button variant="secondary" size="sm" onClick={() => loadSection(ui.key)}>Try again</Button>}>
        {st.error}
      </Alert>
    );
  }
  if (!meta || !st.data) return <Skeleton className="h-40 w-full" />;

  const remove = async () => {
    if (!deleting) return;
    const result = await saveSection(ui.key, (cur) => cur.filter((r) => r.id !== deleting.id));
    if (result.ok) {
      setDeleting(null);
      setDeleteError(null);
    } else {
      setDeleteError(result.error.message);
    }
  };

  return (
    <div className="space-y-3">
      {ui.summary && <SummaryStrip items={ui.summary} summary={st.data.summary} />}
      {ui.hint && <p className="text-xs italic text-muted">{ui.hint}</p>}

      {records.length === 0 ? (
        <EmptyState
          action={
            editable ? (
              <Button id={`add-${ui.key}`} variant="secondary" onClick={() => setDialog({ mode: "add" })}>
                {ui.addLabel ?? "Add"}
              </Button>
            ) : undefined
          }
        >
          {ui.emptyText ?? "Nothing added yet."}
        </EmptyState>
      ) : (
        <>
          {showBar && (
            <div role="search" aria-label={`Filter ${ui.title}`} className="flex flex-wrap items-end gap-3 rounded-md border border-line bg-canvas/60 px-3 py-2.5">
              <div className="min-w-[12rem] flex-1">
                <label htmlFor={`${ui.key}-search`} className="mb-1 block text-xs font-semibold uppercase tracking-wide text-muted">Search</label>
                <input
                  id={`${ui.key}-search`}
                  type="search"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Type to narrow the list"
                  className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm"
                />
              </div>
              {filterFields.map((f) => (
                <div key={f.name}>
                  <label htmlFor={`${ui.key}-${f.name}`} className="mb-1 block text-xs font-semibold uppercase tracking-wide text-muted">{f.label}</label>
                  <select
                    id={`${ui.key}-${f.name}`}
                    value={chosen[f.name] ?? ""}
                    onChange={(e) => setChosen((cur) => ({ ...cur, [f.name]: e.target.value }))}
                    className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm"
                  >
                    <option value="">All</option>
                    {f.options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                  </select>
                </div>
              ))}
              {active && <Button variant="ghost" size="sm" onClick={clearFilters}>Clear filters</Button>}
              <p role="status" className="ml-auto self-center text-xs text-muted">{active ? `Showing ${shown.length} of ${records.length}` : `${records.length} records`}</p>
            </div>
          )}
          {shown.length === 0 && (
            <EmptyState action={<Button variant="secondary" onClick={clearFilters}>Clear filters</Button>}>No records match these filters.</EmptyState>
          )}

          {groups.map((g) => (
            <section key={g.key} aria-label={g.title ?? ui.title} className="space-y-2">
              {g.title && <h3 className="font-display text-lg font-medium text-navy">{g.title}</h3>}

              {/* Tablet and desktop: table */}
              <div className="hidden overflow-x-auto rounded-md border border-line-strong/70 md:block">
                <table className="w-full min-w-[40rem] border-collapse text-left text-[13px] leading-snug">
                  <caption className="sr-only">{g.title ? `${ui.title}: ${g.title}` : ui.title}</caption>
                  <thead>
                    <tr className="bg-brand-soft text-navy">
                      <th scope="col" className="w-10 px-2 py-2 text-center text-[10.5px] font-bold uppercase tracking-[0.05em]">S. No</th>
                      {columns.map((c) => (
                        <th key={c.header} scope="col" className={`px-2 py-2 align-bottom text-[10.5px] font-bold uppercase leading-tight tracking-[0.05em] ${c.numeric ? "text-right" : ""}`}>{c.header}</th>
                      ))}
                      {editable && <th scope="col" className="px-1 py-2 text-right font-semibold"><span className="sr-only">Actions</span></th>}
                    </tr>
                  </thead>
                  <tbody>
                    {g.rows.map((r, i) => (
                      <tr key={r.id} className="border-t border-line align-top transition-colors hover:bg-warm/40">
                        <td className="px-2 py-2 text-center font-display text-base tabular-nums text-muted">{ui.groupBy ? i + 1 : records.indexOf(r) + 1}</td>
                        {columns.map((c) => (
                          <td key={c.header} className={`px-2 py-2 ${c.numeric ? "text-right tabular-nums" : ""}`}>{c.cell(r)}</td>
                        ))}
                        {editable && (
                          <td className="whitespace-nowrap px-1 py-2 text-right">
                            <RowActions record={r} onEdit={() => setDialog({ mode: "edit", record: r })} onDuplicate={() => setDialog({ mode: "duplicate", record: r })} onDelete={() => { setDeleteError(null); setDeleting(r); }} />
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Phones: one card per record */}
              <ul className="space-y-3 md:hidden">
                {g.rows.map((r, i) => (
                  <li key={r.id} className="rounded-md border border-line bg-surface p-3 shadow-[var(--shadow-paper)]">
                    <p className="mb-2 font-display text-sm italic text-muted">Record {ui.groupBy ? i + 1 : records.indexOf(r) + 1}</p>
                    <dl className="space-y-1.5 text-sm">
                      {columns.map((c) => {
                        const v = c.cell(r);
                        return v ? (
                          <div key={c.header} className="flex justify-between gap-4">
                            <dt className="text-muted">{c.header}</dt>
                            <dd className="text-right font-medium">{v}</dd>
                          </div>
                        ) : null;
                      })}
                    </dl>
                    {editable && (
                      <div className="mt-3 flex justify-end border-t border-line pt-2">
                        <RowActions record={r} onEdit={() => setDialog({ mode: "edit", record: r })} onDuplicate={() => setDialog({ mode: "duplicate", record: r })} onDelete={() => { setDeleteError(null); setDeleting(r); }} />
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          ))}

          {editable && (
            <Button id={`add-${ui.key}`} variant="secondary" onClick={() => setDialog({ mode: "add" })}>
              + {ui.addLabel ?? "Add"}
            </Button>
          )}
        </>
      )}

      {dialog && (
        <RecordDialog
          key={`${dialog.mode}-${dialog.record?.id ?? "new"}`}
          ui={ui}
          meta={meta}
          state={dialog}
          allRecords={st.data.records}
          onClose={() => setDialog(null)}
        />
      )}

      <ConfirmDialog
        open={deleting !== null}
        title="Delete this record?"
        destructive
        confirmLabel="Delete"
        onCancel={() => setDeleting(null)}
        onConfirm={remove}
        message={
          <>
            <p>This removes the record from your appraisal. This cannot be undone.</p>
            {deleteError && <p className="mt-2 font-medium text-bad">Not deleted: {deleteError}</p>}
          </>
        }
      />
    </div>
  );
}

function RowActions({ record, onEdit, onDuplicate, onDelete }: { record: Rec; onEdit: () => void; onDuplicate: () => void; onDelete: () => void }) {
  return (
    <span className="inline-flex" data-record={record.id}>
      <Button variant="ghost" size="sm" className="px-2" onClick={onEdit}>Edit</Button>
      <Button variant="ghost" size="sm" className="px-2" onClick={onDuplicate}>Duplicate</Button>
      <Button variant="ghost" size="sm" className="px-2 text-bad hover:bg-bad-soft" onClick={onDelete}>Delete</Button>
    </span>
  );
}

function RecordDialog({
  ui,
  meta,
  state,
  allRecords,
  onClose,
}: {
  ui: SectionUi;
  meta: SectionMeta;
  state: NonNullable<DialogState>;
  allRecords: Rec[];
  onClose: () => void;
}) {
  const { saveSection } = useAppraisal();
  const preset = ui.scope ? { [ui.scope.field]: ui.scope.value } : undefined;
  const [values, setValues] = useState<FormValues>(() => toFormValues(meta, state.record, preset));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const hidden = ui.scope ? [ui.scope.field] : [];

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const found = validate(meta, values, hidden);
    setErrors(found);
    setFormError(null);
    if (Object.keys(found).length > 0) {
      // Move focus to the first field with a problem.
      const first = meta.fields.find((f) => found[f.name]);
      if (first) (formRef.current?.elements.namedItem(first.name) as HTMLElement | null)?.focus();
      return;
    }
    setSaving(true);
    const editing = state.mode === "edit" ? state.record : undefined;
    const record = toApiRecord(meta, values, editing?.id);
    const index = editing ? allRecords.findIndex((r) => r.id === editing.id) : allRecords.length;
    const result = await saveSection(ui.key, (cur) => (editing ? cur.map((r) => (r.id === editing.id ? record : r)) : [...cur, record]));
    setSaving(false);
    if (result.ok) {
      onClose();
      return;
    }
    const mapped = serverErrorsFor(result.error.fieldErrors, index);
    setErrors(mapped);
    setFormError(Object.keys(mapped).length > 0 ? "Please fix the highlighted fields." : result.error.message);
  };

  const title = `${state.mode === "edit" ? "Edit" : "Add"}: ${ui.title}`;
  return (
    <Dialog open onClose={onClose} title={title} wide>
      <form ref={formRef} onSubmit={submit} noValidate className="space-y-5">
        {state.mode === "duplicate" && <Alert tone="info" title="Duplicating">Change whatever differs, then save as a new record.</Alert>}
        <RecordFields
          meta={meta}
          values={values}
          errors={errors}
          hidden={hidden}
          suggestions={ui.suggestions}
          onChange={(name, v) => setValues((cur) => ({ ...cur, [name]: v }))}
          autoFocusFirst
        />
        {formError && <p role="alert" className="text-sm font-medium text-bad">{formError}</p>}
        <div className="flex justify-end gap-2 border-t border-line pt-4">
          <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={saving}>{state.mode === "edit" ? "Save changes" : "Save record"}</Button>
        </div>
      </form>
    </Dialog>
  );
}

interface FilterField {
  name: string;
  label: string;
  options: { value: string; label: string }[];
}

/**
 * The drop-down filters for a list: the fields the section names (or, failing that, its choice fields shown as
 * columns), each offered only when the records actually hold two or more different values for it.
 */
function resolveFilters(meta: SectionMeta, ui: SectionUi, records: Rec[]): FilterField[] {
  const byName = new Map<string, FieldMeta>(meta.fields.map((f) => [f.name, f]));
  const names = ui.filters ?? (ui.columns ?? []).filter((c): c is string => typeof c === "string" && byName.get(c)?.type === "ENUM");
  const out: FilterField[] = [];
  for (const name of names) {
    const f = byName.get(name);
    if (!f || (ui.scope && f.name === ui.scope.field)) continue;
    const values = [...new Set(records.map((r) => r[name]).filter((v) => v != null && v !== "").map(String))];
    if (values.length < 2) continue;
    values.sort((a, b) => (f.type === "INT" || f.type === "DECIMAL" ? Number(a) - Number(b) : a.localeCompare(b)));
    out.push({ name, label: ui.headers?.[name] ?? f.label, options: values.map((v) => ({ value: v, label: formatCell(f, v) })) });
  }
  return out;
}

interface ResolvedColumn {
  header: string;
  numeric: boolean;
  cell: (r: Rec) => string;
}

function resolveColumns(meta: SectionMeta, ui: SectionUi): ResolvedColumn[] {
  const byName = new Map<string, FieldMeta>(meta.fields.map((f) => [f.name, f]));
  const cols: Column[] = ui.columns ?? meta.fields.slice(0, 6).map((f) => f.name);
  return cols.map((c) => {
    if (typeof c !== "string") return { header: c.header, numeric: false, cell: c.render };
    const f = byName.get(c)!;
    return {
      header: ui.headers?.[c] ?? f.label,
      numeric: f.type === "INT" || f.type === "DECIMAL",
      cell: (r) => formatCell(f, r[c]),
    };
  });
}
