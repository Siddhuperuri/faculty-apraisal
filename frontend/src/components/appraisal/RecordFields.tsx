"use client";

import { FieldInput, rangeHint } from "@/components/ui/FieldInput";
import type { SectionMeta } from "@/lib/types";
import type { FieldErrors, FormValues } from "@/lib/validate";

/** The fields of one record as a two-column form (long text spans both columns). */
export function RecordFields({
  meta,
  values,
  errors,
  onChange,
  onBlur,
  disabled = false,
  hidden = [],
  suggestions,
  autoFocusFirst = false,
}: {
  meta: SectionMeta;
  values: FormValues;
  errors: FieldErrors;
  onChange: (name: string, value: string) => void;
  onBlur?: (name: string) => void;
  disabled?: boolean;
  hidden?: string[];
  suggestions?: Record<string, string[]>;
  autoFocusFirst?: boolean;
}) {
  const shown = meta.fields.filter((f) => !hidden.includes(f.name));
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {shown.map((f, i) => (
        <div key={f.name} className={f.type === "TEXT" && (f.maxLength ?? 0) > 120 ? "md:col-span-2" : undefined}>
          <FieldInput
            meta={f}
            value={values[f.name] ?? ""}
            error={errors[f.name]}
            onChange={(v) => onChange(f.name, v)}
            onBlur={onBlur ? () => onBlur(f.name) : undefined}
            disabled={disabled}
            hint={rangeHint(f)}
            suggestions={suggestions?.[f.name]}
            autoFocus={autoFocusFirst && i === 0}
          />
        </div>
      ))}
    </div>
  );
}
