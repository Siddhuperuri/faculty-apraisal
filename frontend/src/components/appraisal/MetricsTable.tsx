"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import { enumLabel } from "@/lib/labels";
import type { SectionUi } from "@/lib/formStructure";
import { serverErrorsFor, toApiRecord, toFormValues, validate, type FormValues } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
import { useAutosave } from "./useAutosave";

/**
 * Research profile metrics: the form's fixed grid with one row each for Google Scholar, Scopus and Web of
 * Science. A row you leave empty is not saved; once you start a row, empty cells count as 0.
 */
export function MetricsTable({ ui }: { ui: SectionUi }) {
  const { section, loadSection, metas, appraisal, saveSection, setDirty } = useAppraisal();
  const [edits, setEdits] = useState<Record<string, FormValues> | null>(null);
  const [serverMessage, setServerMessage] = useState<string | null>(null);

  useEffect(() => loadSection(ui.key), [loadSection, ui.key]);

  const st = section(ui.key);
  const meta = metas?.[ui.key];
  const editable = appraisal?.editable ?? false;
  const key = ui.fixedBy ?? "platform";
  const platforms = useMemo(() => meta?.fields.find((f) => f.name === key)?.allowed ?? [], [meta, key]);
  const numeric = useMemo(() => meta?.fields.filter((f) => f.name !== key) ?? [], [meta, key]);

  const base = useMemo(() => {
    const out: Record<string, FormValues> = {};
    for (const p of platforms) {
      const rec = st.data?.records.find((r) => r[key] === p);
      out[p] = meta ? toFormValues(meta, rec ?? null, { [key]: p }) : {};
    }
    return out;
  }, [platforms, st.data, meta, key]);

  const rows = edits ?? base;

  /** A started row has its empty cells filled with 0; an untouched row is left out entirely. */
  const filled = useCallback(
    (p: string): FormValues | null => {
      const row = rows[p] ?? {};
      const started = numeric.some((f) => (row[f.name] ?? "").trim() !== "");
      if (!started) return null;
      const out: FormValues = { ...row, [key]: p };
      for (const f of numeric) if ((out[f.name] ?? "").trim() === "") out[f.name] = "0";
      return out;
    },
    [rows, numeric, key],
  );

  const errors = useMemo(() => {
    const out: Record<string, Record<string, string>> = {};
    if (!meta) return out;
    for (const p of platforms) {
      const f = filled(p);
      if (f) out[p] = validate(meta, f);
    }
    return out;
  }, [meta, platforms, filled]);
  const canSave = edits !== null && Object.values(errors).every((e) => Object.keys(e).length === 0);

  const save = useCallback(() => {
    if (!meta || !edits) return;
    const snapshot = edits;
    void saveSection(ui.key, (cur) =>
      platforms.flatMap((p) => {
        const f = filled(p);
        if (!f) return [];
        return [toApiRecord(meta, f, cur.find((r) => r[key] === p)?.id)];
      }),
    ).then((r) => {
      if (r.ok) {
        setEdits((now) => (now === snapshot ? null : now));
        setServerMessage(null);
      } else {
        const anyField = Object.keys(serverErrorsFor(r.error.fieldErrors, 0)).length > 0;
        setServerMessage(anyField ? "Some values were not accepted. Check the numbers." : r.error.message);
      }
    });
  }, [meta, edits, platforms, filled, saveSection, ui.key, key]);

  useAutosave({ pending: edits !== null, canSave, save });

  useEffect(() => {
    setDirty(`${ui.key}#grid`, edits !== null);
    return () => setDirty(`${ui.key}#grid`, false);
  }, [edits, setDirty, ui.key]);

  if (st.error) {
    return (
      <Alert tone="error" title="Could not load this section" action={<Button variant="secondary" size="sm" onClick={() => loadSection(ui.key)}>Try again</Button>}>
        {st.error}
      </Alert>
    );
  }
  if (!meta || !st.data) return <Skeleton className="h-32 w-full" />;

  const set = (p: string, name: string, v: string) => setEdits({ ...rows, [p]: { ...rows[p], [name]: v } });

  return (
    <div className="space-y-2">
      <div className="overflow-x-auto rounded-md border border-line-strong/70">
        <table className="w-full min-w-[36rem] border-collapse text-sm">
          <caption className="sr-only">{ui.title}</caption>
          <thead>
            <tr className="bg-brand-soft text-navy">
              <th scope="col" className="px-3 py-2 text-left text-[11px] font-bold uppercase tracking-[0.08em]">Platform</th>
              {numeric.map((f) => (
                <th key={f.name} scope="col" className="px-3 py-2 text-left text-[11px] font-bold uppercase tracking-[0.08em]">{f.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {platforms.map((p) => (
              <tr key={p} className="border-t border-line">
                <th scope="row" className="px-3 py-2 text-left font-display text-base font-medium">{enumLabel(p)}</th>
                {numeric.map((f) => {
                  const err = errors[p]?.[f.name];
                  return (
                    <td key={f.name} className="px-2 py-1.5">
                      <input
                        type="text"
                        inputMode="numeric"
                        autoComplete="off"
                        value={rows[p]?.[f.name] ?? ""}
                        disabled={!editable}
                        aria-label={`${enumLabel(p)}: ${f.label}`}
                        aria-invalid={err ? true : undefined}
                        onChange={(e) => set(p, f.name, e.target.value)}
                        className={`w-full rounded-sm border bg-surface px-2 py-1.5 text-sm tabular-nums disabled:bg-canvas disabled:text-muted ${err ? "border-bad" : "border-line-strong"}`}
                      />
                      {err && <p className="mt-0.5 text-xs font-medium text-bad">{err}</p>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {serverMessage && <p role="alert" className="text-sm font-medium text-bad">{serverMessage}</p>}
    </div>
  );
}
