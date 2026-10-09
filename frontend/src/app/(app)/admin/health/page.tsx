"use client";

import { useCallback, useEffect, useState } from "react";
import { BackupPanel, StoragePanel } from "@/components/admin/HealthPanels";
import { ConsoleHeader } from "@/components/console/parts";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { get } from "@/lib/api";
import type { BackupHealth, StorageHealth } from "@/lib/types";

export default function SystemHealthPage() {
  const [backup, setBackup] = useState<BackupHealth | null>(null);
  const [storage, setStorage] = useState<StorageHealth | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      const [b, s] = await Promise.all([get<BackupHealth>("/api/admin/backup-health"), get<StorageHealth>("/api/admin/storage-health")]);
      setBackup(b);
      setStorage(s);
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void load();
  }, [load]);

  if (error) {
    return <Alert tone="error" title="Could not load the health checks" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!backup || !storage) {
    return (
      <div className="space-y-4" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-12 w-1/2" />
        <Skeleton className="h-56 w-full" />
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <ConsoleHeader eyebrow="Administration" title="System health" aside={<RefreshButton onRefresh={load} loadedAt={loadedAt} />}>
        Whether the college&apos;s data is backed up, and whether there is room to keep it.
      </ConsoleHeader>
      <BackupPanel health={backup} onRecorded={() => void load()} />
      <StoragePanel health={storage} />
    </div>
  );
}
