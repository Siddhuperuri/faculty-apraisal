"use client";

import { FieldInput, rangeHint } from "@/components/ui/FieldInput";
import { formatDate } from "@/lib/format";
import { choicesFor, isAsked } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
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
  radios = [],
  fieldHints,
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
  /** Choice fields shown as radio buttons rather than a drop-down. */
  radios?: string[];
  /** Helper text shown under a field, by field name. */
  fieldHints?: Record<string, string>;
  autoFocusFirst?: boolean;
}) {
  const { appraisal } = useAppraisal();
  const year = appraisal ? { start: appraisal.academicYearStart, end: appraisal.academicYearEnd } : undefined;
  const shown = meta.fields.filter((f) => !hidden.includes(f.name) && isAsked(f, values));
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {shown.map((f, i) => {
        // A dependent choice offers only what suits the value chosen above it; a derived field is worked out, not typed.
        const parent = f.dependsOn ? meta.fields.find((x) => x.name === f.dependsOn) : undefined;
        const waiting = parent !== undefined && !(values[parent.name] ?? "");
        const offered = f.dependsOn ? { ...f, allowed: choicesFor(f, values) } : f;
        const inYear = f.inAcademicYear && year;
        const hint = f.derived
          ? "Worked out from the dates above."
          : waiting
            ? `Choose the ${parent.label.toLowerCase()} first.`
            : inYear
              ? f.type === "INT"
                ? `${year.start.slice(0, 4)} or ${year.end.slice(0, 4)} (the academic year being appraised)`
                : `Within the academic year ${formatDate(year.start)} to ${formatDate(year.end)}`
              : (fieldHints?.[f.name] ?? rangeHint(f));
        return (
          <div key={f.name} className={f.type === "TEXT" && (f.maxLength ?? 0) > 120 ? "md:col-span-2" : undefined}>
            <FieldInput
              meta={offered}
              value={values[f.name] ?? ""}
              error={errors[f.name]}
              onChange={(v) => onChange(f.name, v)}
              onBlur={onBlur ? () => onBlur(f.name) : undefined}
              disabled={disabled || f.derived === true || waiting}
              hint={hint}
              suggestions={suggestions?.[f.name]}
              autoFocus={autoFocusFirst && i === 0}
              radio={radios.includes(f.name)}
              range={inYear ? year : undefined}
            />
          </div>
        );
      })}
    </div>
  );
}
