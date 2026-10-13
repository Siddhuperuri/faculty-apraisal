"use client";

import { FieldInput } from "@/components/ui/FieldInput";
import { choicesFor, isAsked } from "@/lib/validate";
import { useAppraisal } from "./AppraisalProvider";
import type { SectionMeta } from "@/lib/types";
import type { FieldErrors, FormValues } from "@/lib/validate";

/**
 * The fields of one record as a two-column form (long text spans both columns). Nothing is printed under a field to say
 * what range it takes: a wrong value is named in red as it is typed or when the field is left.
 */
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
  scopeValue,
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
  /** The value of the view's scope (institute or department level), which picks the suggestions offered for a field. */
  scopeValue?: string;
  autoFocusFirst?: boolean;
}) {
  const { appraisal } = useAppraisal();
  const year = appraisal ? { start: appraisal.academicYearStart, end: appraisal.academicYearEnd, name: appraisal.academicYear } : undefined;
  // The wording a standard role resolves to (or the typed one) is worked out by the server, so it is not a control here.
  // A field asked for only under another choice is hidden until then, or (an amount) shown greyed out and empty.
  const shown = meta.fields.filter((f) => !hidden.includes(f.name) && !f.effective && (isAsked(f, values) || f.onlyWhenDisabled === true));
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {shown.map((f, i) => {
        // A dependent choice offers only what suits the value chosen above it; a derived field is worked out, not typed.
        const parent = f.dependsOn ? meta.fields.find((x) => x.name === f.dependsOn) : undefined;
        const waiting = parent !== undefined && !(values[parent.name] ?? "");
        const offered = f.dependsOn ? { ...f, allowed: choicesFor(f, values) } : f;
        const inYear = f.inAcademicYear && year;
        const asked = isAsked(f, values);
        const hint = f.derived ? "Worked out from the dates above." : waiting ? `Choose the ${parent.label.toLowerCase()} first.` : undefined;
        return (
          <div key={f.name} className={f.type === "TEXT" && ((f.maxLength ?? 0) > 120 || f.maxWords != null) ? "md:col-span-2" : undefined}>
            <FieldInput
              meta={offered}
              value={asked ? (values[f.name] ?? "") : ""}
              error={asked ? errors[f.name] : undefined}
              onChange={(v) => onChange(f.name, v)}
              onBlur={onBlur ? () => onBlur(f.name) : undefined}
              disabled={disabled || f.derived === true || waiting || !asked}
              hint={hint}
              suggestions={suggestions?.[f.name] ?? f.suggestions?.[scopeValue ?? ""] ?? f.suggestions?.[""] ?? undefined}
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
