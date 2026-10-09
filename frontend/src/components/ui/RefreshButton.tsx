"use client";

import { useCallback, useEffect, useState } from "react";
import { ago } from "@/lib/format";
import { Spinner } from "@/components/ui/primitives";

/** Remembers when a page last loaded its data successfully; call `stamp` after each successful load. */
export function useLoadedAt(): [Date | null, () => void] {
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);
  const stamp = useCallback(() => setLoadedAt(new Date()), []);
  return [loadedAt, stamp];
}

/**
 * A manual Refresh button with "Updated just now" beside it, for pages that other people's actions change (a message
 * from the HoD, an approval). The time moves on by itself; the announcement is polite so screen readers hear it
 * without losing their place.
 */
export function RefreshButton({ onRefresh, loadedAt }: { onRefresh: () => Promise<void>; loadedAt: Date | null }) {
  const [busy, setBusy] = useState(false);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 15_000);
    return () => clearInterval(t);
  }, []);

  const refresh = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await onRefresh();
    } finally {
      setNow(new Date());
      setBusy(false);
    }
  };

  return (
    <div className="inline-flex items-center gap-2 text-xs text-muted">
      <span role="status" aria-live="polite">
        {busy ? "Refreshing…" : loadedAt ? `Updated ${ago(loadedAt, now)}` : ""}
      </span>
      <button
        type="button"
        onClick={() => void refresh()}
        disabled={busy}
        className="inline-flex min-h-9 items-center gap-2 rounded-full border border-line-strong bg-surface px-3.5 text-sm font-semibold text-ink shadow-[0_1px_3px_rgb(20_30_54/0.05)] transition-colors hover:bg-brand-soft disabled:cursor-not-allowed disabled:opacity-60"
      >
        {busy ? <Spinner /> : <span aria-hidden>↻</span>}
        Refresh
      </button>
    </div>
  );
}
