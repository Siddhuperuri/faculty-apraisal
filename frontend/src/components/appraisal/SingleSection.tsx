"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import type { SectionUi } from "@/lib/formStructure";
import { isBlank, serverErrorsFor, toApiRecord, toFormValues, validate, type FieldErrors, type FormValues } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
import { RecordFields } from "./RecordFields";
import { useAutosave } from "./useAutosave";

/**
 * A section with one record (Part A, total students mentored, own Ph.D. progress, other contributions).
 * It saves by itself shortly after the last keystroke, and only when the values are valid, so a half-typed
 * number is never sent. Errors appear once you leave a field. Clearing every field removes the record.
 */
export function SingleSection({ ui }: { ui: SectionUi }) {
  const { section, loadSection, metas, appraisal, saveSection, setDirty } = useAppraisal();
  const [edits, setEdits] = useState<FormValues | null>(null);
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [serverErrors, setServerErrors] = useState<FieldErrors>({});

  useEffect(() => loadSection(ui.key), [loadSection, ui.key]);

  const st = section(ui.key);
  const meta = metas?.[ui.key];
  const editable = appraisal?.editable ?? false;
  const record = st.data?.records[0];

  const base = useMemo(() => (meta ? toFormValues(meta, record) : null), [meta, record]);
  const values = edits ?? base ?? {};
  const errors = useMemo(() => (meta && edits ? validate(meta, edits) : {}), [meta, edits]);
  const canSave = edits !== null && (isBlank(edits) || Object.keys(errors).length === 0);

  const save = useCallback(() => {
    if (!meta || !edits) return;
    const snapshot = edits;
    const blank = isBlank(snapshot);
    void saveSection(ui.key, (cur) => (blank ? [] : [toApiRecord(meta, snapshot, cur[0]?.id)])).then((r) => {
      if (r.ok) {
        setEdits((now) => (now === snapshot ? null : now)); // typed more meanwhile? keep it
        setServerErrors({});
      } else {
        setServerErrors(serverErrorsFor(r.error.fieldErrors, 0));
      }
    });
  }, [meta, edits, saveSection, ui.key]);

  useAutosave({ pending: edits !== null, canSave, save });

  useEffect(() => {
    setDirty(`${ui.key}#form`, edits !== null);
    return () => setDirty(`${ui.key}#form`, false);
  }, [edits, setDirty, ui.key]);

  if (st.error) {
    return (
      <Alert tone="error" title="Could not load this section" action={<Button variant="secondary" size="sm" onClick={() => loadSection(ui.key)}>Try again</Button>}>
        {st.error}
      </Alert>
    );
  }
  if (!meta || !st.data) return <Skeleton className="h-40 w-full" />;

  const shown: FieldErrors = { ...serverErrors };
  for (const f of meta.fields) if (touched[f.name] && errors[f.name]) shown[f.name] = errors[f.name];

  return (
    <div className="space-y-3">
      <RecordFields
        meta={meta}
        values={values}
        errors={shown}
        disabled={!editable}
        suggestions={ui.suggestions}
        onChange={(name, v) => setEdits({ ...values, [name]: v })}
        onBlur={(name) => setTouched((t) => ({ ...t, [name]: true }))}
      />
      {editable && edits !== null && !canSave && (
        <p className="text-sm text-warn">Not saved yet: some fields need attention. What you typed is kept on this page.</p>
      )}
    </div>
  );
}
