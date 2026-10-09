"use client";

import { useEffect, useRef } from "react";

/**
 * Debounced autosave. Saves `delay` ms after the last change, but only when the values are valid. If the
 * user navigates away first, the pending change is flushed on unmount (the save itself lives in the
 * appraisal provider, which outlives the page), so nothing typed is lost by clicking a link.
 */
export function useAutosave({ pending, canSave, save, delay = 500 }: { pending: boolean; canSave: boolean; save: () => void; delay?: number }) {
  const saveRef = useRef(save);
  const shouldSave = useRef(false);

  useEffect(() => {
    saveRef.current = save;
    shouldSave.current = pending && canSave;
  });

  useEffect(() => {
    if (!pending || !canSave) return;
    const t = setTimeout(() => saveRef.current(), delay);
    return () => clearTimeout(t);
  }, [pending, canSave, delay, save]);

  useEffect(
    () => () => {
      if (shouldSave.current) saveRef.current();
    },
    [],
  );
}
