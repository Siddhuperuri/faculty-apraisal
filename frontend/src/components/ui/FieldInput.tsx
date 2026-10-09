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
    "aria-required": meta.required || undefined,
    onBlur,
  } as const;

  let control: React.ReactNode;
  if (meta.type === "ENUM") {
    control = (
      <select {...common} onChange={(e) => onChange(e.target.value)} className={`${CONTROL} ${border}`}>
        <option value="">Select…</option>
        {meta.allowed?.map((a) => (
          <option key={a} value={a}>
            {enumLabel(a)}
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
        {meta.required || meta.submitRequired ? (
          <>
            <span aria-hidden className="ml-0.5 text-bad">
              *
            </span>
            <span className="sr-only"> (required{meta.required ? "" : " to submit"})</span>
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
