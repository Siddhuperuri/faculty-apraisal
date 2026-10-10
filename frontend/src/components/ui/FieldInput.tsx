"use client";

import { useId } from "react";
import { enumLabel } from "@/lib/labels";
import type { FieldMeta } from "@/lib/types";

const CONTROL =
  "block w-full rounded-sm border bg-surface px-3 py-2 text-[15px] text-ink shadow-[inset_0_1px_2px_rgb(20_30_54/0.06)] transition-shadow placeholder:text-muted focus:border-brand focus:shadow-[0_0_0_3px_rgb(0_112_192/0.15)] disabled:cursor-not-allowed disabled:bg-canvas disabled:text-muted disabled:shadow-none";

/**
 * One form control, chosen from the field's server-supplied metadata: label above, helper below, error
 * directly under the control and tied to it for screen readers. Required and optional are both stated.
 */
export function FieldInput({
  meta,
  value,
  error,
  onChange,
  onBlur,
  disabled = false,
  hint,
  suggestions,
  autoFocus = false,
  range,
  radio = false,
}: {
  meta: FieldMeta;
  value: string;
  error?: string;
  onChange: (v: string) => void;
  onBlur?: () => void;
  disabled?: boolean;
  hint?: string;
  suggestions?: string[];
  autoFocus?: boolean;
  /** The first and last day (YYYY-MM-DD) a date or month input offers; the whole calendar when omitted. */
  range?: { start: string; end: string };
  /** Show a choice with few options as radio buttons (one can be picked) instead of a drop-down. */
  radio?: boolean;
}) {
  const uid = useId();
  const id = `f-${uid}`;
  const describedBy = [error ? `${id}-err` : null, hint ? `${id}-hint` : null].filter(Boolean).join(" ") || undefined;
  const border = error ? "border-bad" : "border-line-strong";
  const common = {
    id,
    name: meta.name,
    value,
    disabled,
    autoFocus,
    "aria-invalid": error ? true : undefined,
    "aria-describedby": describedBy,
    "aria-required": meta.required || meta.onlyWhenField != null || undefined,
    onBlur,
  } as const;

  // Asked for only under another choice (a platform name when the platform is Other): required while it is shown.
  const required = meta.required || meta.onlyWhenField != null;

  if (radio && meta.type === "ENUM") {
    return (
      <fieldset aria-describedby={describedBy} aria-invalid={error ? true : undefined}>
        <legend className="mb-1 block text-[13px] font-semibold tracking-wide">
          {meta.label}
          <span aria-hidden className="ml-0.5 text-bad">
            *
          </span>
          <span className="sr-only"> (required)</span>
        </legend>
        <div className={`flex flex-wrap gap-x-5 gap-y-2 rounded-sm border bg-surface px-3 py-2 ${border}`}>
          {meta.allowed?.map((a, i) => (
            <label key={a} className="inline-flex cursor-pointer items-center gap-2 text-[15px]">
              <input
                type="radio"
                name={meta.name}
                value={a}
                checked={value === a}
                disabled={disabled}
                autoFocus={autoFocus && i === 0}
                onChange={() => onChange(a)}
                onBlur={onBlur}
                className="h-4 w-4 accent-[var(--color-brand,#0070c0)]"
              />
              {enumLabel(a)}
            </label>
          ))}
        </div>
        {hint && (
          <p id={`${id}-hint`} className="mt-1 text-xs text-muted">
            {hint}
          </p>
        )}
        {error && (
          <p id={`${id}-err`} className="mt-1 text-sm font-medium text-bad">
            {error}
          </p>
        )}
      </fieldset>
    );
  }

  let control: React.ReactNode;
  const numericChoice = (meta.type === "INT" || meta.type === "DECIMAL") && (meta.allowed?.length ?? 0) > 0;
  if (meta.type === "ENUM" || numericChoice) {
    control = (
      <select {...common} onChange={(e) => onChange(e.target.value)} className={`${CONTROL} ${border}`}>
        <option value="">Select…</option>
        {meta.allowed?.map((a) => (
          <option key={a} value={a}>
            {numericChoice ? a : enumLabel(a)}
          </option>
        ))}
      </select>
    );
  } else if (meta.type === "TEXT" && (meta.maxLength ?? 0) > 300) {
    control = (
      <>
        <textarea {...common} rows={5} maxLength={meta.maxLength ?? undefined} onChange={(e) => onChange(e.target.value)} className={`${CONTROL} ${border}`} />
        <p className="mt-1 text-right text-xs text-muted" aria-hidden>
          {value.length} / {meta.maxLength}
        </p>
      </>
    );
  } else if (meta.type === "DATE" || meta.type === "MONTH_YEAR") {
    control = (
      <input
        {...common}
        type={meta.type === "DATE" ? "date" : "month"}
        min={meta.type === "DATE" ? (range?.start ?? "1950-01-01") : range?.start.slice(0, 7)}
        max={meta.type === "DATE" ? (range?.end ?? "2100-12-31") : range?.end.slice(0, 7)}
        onChange={(e) => onChange(e.target.value)}
        className={`${CONTROL} ${border}`}
      />
    );
  } else {
    const numeric = meta.type === "INT" || meta.type === "DECIMAL";
    const list = suggestions && suggestions.length > 0 ? `${id}-list` : undefined;
    control = (
      <>
        <input
          {...common}
          type="text"
          list={list}
          inputMode={meta.type === "INT" ? "numeric" : meta.type === "DECIMAL" ? "decimal" : undefined}
          maxLength={meta.type === "TEXT" ? (meta.maxLength ?? undefined) : undefined}
          autoComplete="off"
          onChange={(e) => onChange(e.target.value)}
          className={`${CONTROL} ${border} ${numeric ? "tabular-nums" : ""}`}
        />
        {list && (
          <datalist id={list}>
            {suggestions!.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
        )}
      </>
    );
  }

  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-[13px] font-semibold tracking-wide">
        {meta.label}
        {required || meta.submitRequired ? (
          <>
            <span aria-hidden className="ml-0.5 text-bad">
              *
            </span>
            <span className="sr-only"> (required{required ? "" : " to submit"})</span>
          </>
        ) : (
          <span className="ml-1 text-xs font-normal text-muted">(optional)</span>
        )}
      </label>
      {control}
      {hint && (
        <p id={`${id}-hint`} className="mt-1 text-xs text-muted">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-err`} className="mt-1 text-sm font-medium text-bad">
          {error}
        </p>
      )}
    </div>
  );
}

export function rangeHint(meta: FieldMeta): string | undefined {
  if ((meta.type === "INT" || meta.type === "DECIMAL") && meta.min != null && meta.max != null && meta.max <= 100000) {
    return `Between ${meta.min} and ${meta.max}`;
  }
  return undefined;
}

/** A labelled drop-down whose options are not fixed codes (departments, cadres, academic years). */
export function ChoiceInput({
  label,
  value,
  options,
  onChange,
  error,
  required = false,
  disabled = false,
  placeholder = "Select…",
}: {
  label: string;
  value: string;
  options: { value: string; label: string }[];
  onChange: (v: string) => void;
  error?: string;
  required?: boolean;
  disabled?: boolean;
  placeholder?: string;
}) {
  const uid = useId();
  const id = `c-${uid}`;
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-[13px] font-semibold tracking-wide">
        {label}
        {required ? (
          <>
            <span aria-hidden className="ml-0.5 text-bad">
              *
            </span>
            <span className="sr-only"> (required)</span>
          </>
        ) : (
          <span className="ml-1 text-xs font-normal text-muted">(optional)</span>
        )}
      </label>
      <select
        id={id}
        value={value}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-err` : undefined}
        aria-required={required || undefined}
        onChange={(e) => onChange(e.target.value)}
        className={`${CONTROL} ${error ? "border-bad" : "border-line-strong"}`}
      >
        <option value="">{placeholder}</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
      {error && (
        <p id={`${id}-err`} className="mt-1 text-sm font-medium text-bad">
          {error}
        </p>
      )}
    </div>
  );
}
