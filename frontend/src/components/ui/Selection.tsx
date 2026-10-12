"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { Button } from "@/components/ui/primitives";
import { barVisible, selectedLabel, type CheckState, type Selection } from "@/lib/selection";

const BOX = "h-4 w-4 cursor-pointer rounded-sm border-line-strong accent-brand align-middle";

/** The checkbox in a table header: for everything shown. It is ticked, half ticked or empty. */
export function SelectAllCheckbox({ state, onChange, label, disabled = false }: {
  state: CheckState;
  onChange: () => void;
  label: string;
  disabled?: boolean;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = state === "some";
  }, [state]);
  return (
    <input
      ref={ref}
      type="checkbox"
      className={BOX}
      checked={state === "all"}
      disabled={disabled}
      aria-label={label}
      aria-checked={state === "some" ? "mixed" : state === "all"}
      onChange={onChange}
    />
  );
}

/** The checkbox on one row. */
export function RowCheckbox({ checked, onChange, label }: { checked: boolean; onChange: () => void; label: string }) {
  return <input type="checkbox" className={BOX} checked={checked} aria-label={label} onChange={onChange} />;
}

/**
 * The bar of bulk actions. It is not there at all until a row is chosen; then it says how many are, how many of those a
 * filter is hiding, lets the choice be cleared, and holds the actions it is given.
 */
export function SelectionToolbar({ selection, hidden, singular, plural, onClear, children }: {
  selection: Selection;
  /** Chosen rows that the current filters do not show. */
  hidden: number;
  singular: string;
  plural: string;
  onClear: () => void;
  children: ReactNode;
}) {
  if (!barVisible(selection)) return null;
  return (
    <div
      role="region"
      aria-label="Actions for the selected rows"
      className="sticky top-2 z-20 flex flex-wrap items-center gap-3 rounded-xl border border-brand bg-brand-soft px-4 py-2.5 shadow-[var(--shadow-lift)]"
    >
      <p role="status" className="text-sm font-semibold text-navy">
        {selectedLabel(selection.size, singular, plural)}
        {hidden > 0 && <span className="ml-2 font-normal text-muted">({hidden} hidden by the filters)</span>}
      </p>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
      <Button variant="ghost" size="sm" className="ml-auto" onClick={onClear}>Clear selection</Button>
    </div>
  );
}
