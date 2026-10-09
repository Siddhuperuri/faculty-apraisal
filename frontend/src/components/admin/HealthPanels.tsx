"use client";

import { useState } from "react";
import { Alert, Badge, Button, Card, RuledHeading } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import { ApiError, post } from "@/lib/api";
import { formatBytes, formatDate, formatDateTime } from "@/lib/format";
import type { BackupHealth, HealthLevel, RestoreTest, StorageHealth } from "@/lib/types";

const TONE = { ok: "ok", warn: "warn", problem: "bad" } as const;
const WORD: Record<HealthLevel, string> = { ok: "Healthy", warn: "Needs attention", problem: "Problem" };
const ALERT = { ok: "ok", warn: "warn", problem: "error" } as const;

export function LevelBadge({ level }: { level: HealthLevel }) {
  return <Badge tone={TONE[level]}>{WORD[level]}</Badge>;
}

/** "3 hours ago", "2 days ago": for ages already reduced to hours. */
function ageText(hours: number | null): string {
  if (hours === null) return "never";
  if (hours < 1) return "less than an hour ago";
  if (hours < 48) return `${Math.round(hours)} hour${Math.round(hours) === 1 ? "" : "s"} ago`;
  return `${Math.round(hours / 24)} days ago`;
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[11px] font-bold uppercase tracking-[0.12em] text-muted">{label}</dt>
      <dd className="mt-0.5 break-words text-sm">{children}</dd>
    </div>
  );
}

const DESTINATION: Record<BackupHealth["destinationStatus"], { tone: "ok" | "warn" | "bad" | "neutral"; word: string }> = {
  OK: { tone: "ok", word: "Reachable" },
  NOT_SET: { tone: "neutral", word: "Not set" },
  MISSING: { tone: "bad", word: "Cannot be found" },
  NOT_WRITABLE: { tone: "bad", word: "Cannot be written to" },
  SAME_DISK: { tone: "warn", word: "Same disk as the data" },
};

/**
 * When the last backup was made, how big it was, whether the place it goes to is working, and when a restore was last
 * proved to work. An overdue backup is shown as a problem, in red, above everything else.
 */
export function BackupPanel({ health, onRecorded }: { health: BackupHealth; onRecorded: () => void }) {
  const [recording, setRecording] = useState(false);
  const dest = DESTINATION[health.destinationStatus];
  const test = health.lastRestoreTest;

  return (
    <section aria-labelledby="backup-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <RuledHeading id="backup-h">Backup health</RuledHeading>
        <LevelBadge level={health.level} />
      </div>
      <Alert tone={ALERT[health.level]} title={health.headline}>
        {health.level === "problem" || health.lastFailureMessage ? (
          <>
            {health.level === "problem" && <p>The college&apos;s appraisal data is at risk until a backup completes. Run <span className="font-mono">scripts\backup.ps1</span> now, and check the schedule.</p>}
            {health.lastFailureMessage && <p className="mt-1">Last failure ({formatDateTime(health.lastFailureAt)}): {health.lastFailureMessage}</p>}
          </>
        ) : undefined}
      </Alert>

      <Card className="p-4 sm:p-5">
        <dl className="grid gap-x-8 gap-y-4 sm:grid-cols-2 lg:grid-cols-3">
          <Fact label="Last backup">
            {health.lastBackupAt ? <>{formatDateTime(health.lastBackupAt)} <span className="text-muted">({ageText(health.ageHours)})</span></> : <span className="text-bad">None yet</span>}
          </Fact>
          <Fact label="Size">
            {health.lastBackupBytes != null ? formatBytes(health.lastBackupBytes) : "—"}
            {health.lastBackupFiles != null && <span className="text-muted"> · {health.lastBackupFiles} stored file{health.lastBackupFiles === 1 ? "" : "s"}</span>}
          </Fact>
          <Fact label="Expected at least every">{health.maxAgeHours} hours</Fact>
          <Fact label="Destination">
            <span className="font-mono text-[13px]">{health.destination || "—"}</span>
            <span className="mt-1 block"><Badge tone={dest.tone}>{dest.word}</Badge></span>
          </Fact>
          <Fact label="Free space at the destination">{health.destinationFreeBytes != null ? formatBytes(health.destinationFreeBytes) : "—"}</Fact>
          <Fact label="Last restore test">
            {test ? (
              <>
                {formatDate(test.testedOn)} <Badge tone={test.result === "PASSED" ? "ok" : "bad"}>{test.result === "PASSED" ? "Passed" : "Failed"}</Badge>
                {test.notes && <span className="mt-1 block text-muted">{test.notes}</span>}
                <span className="block text-xs text-muted">Recorded by {test.recordedBy}</span>
              </>
            ) : (
              <span className="text-warn">Never tested</span>
            )}
            {health.restoreTestOverdue && test && <span className="mt-1 block text-xs text-warn">More than {health.restoreTestMaxAgeDays} days ago: test again.</span>}
          </Fact>
        </dl>
        {health.destinationStatus !== "OK" && <p className="mt-4 text-sm text-muted">{health.destinationNote}</p>}
        <div className="mt-4 flex flex-wrap items-center gap-3 border-t border-line pt-4">
          <Button variant="secondary" onClick={() => setRecording(true)}>Record a restore test</Button>
          <p className="text-xs text-muted">Restore a backup on a spare machine (see Handover), then record the result here.</p>
        </div>
      </Card>

      <RestoreTestDialog open={recording} onClose={() => setRecording(false)} onSaved={() => { setRecording(false); onRecorded(); }} />
    </section>
  );
}

function RestoreTestDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: (t: RestoreTest) => void }) {
  return (
    <Dialog open={open} onClose={onClose} title="Record a restore test">
      <RestoreTestForm onClose={onClose} onSaved={onSaved} />
    </Dialog>
  );
}

function RestoreTestForm({ onClose, onSaved }: { onClose: () => void; onSaved: (t: RestoreTest) => void }) {
  const today = new Date().toISOString().slice(0, 10);
  const [testedOn, setTestedOn] = useState(today);
  const [result, setResult] = useState<"PASSED" | "FAILED">("PASSED");
  const [notes, setNotes] = useState("");
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErrors({});
    setProblem(null);
    try {
      onSaved(await post<RestoreTest>("/api/admin/restore-tests", { testedOn, result, notes }));
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setErrors(err.fieldErrors);
      else setProblem(err instanceof ApiError ? err.message : "Could not save. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={save} className="space-y-4" noValidate>
      <p className="text-sm text-muted">A restore test means taking a backup folder to another machine, running <span className="font-mono">scripts\restore.ps1</span> and signing in to check the data is there.</p>
      {problem && <Alert tone="error" title="Not saved">{problem}</Alert>}
      <div>
        <label htmlFor="rt-date" className="mb-1 block text-sm font-medium">Date of the test</label>
        <input id="rt-date" type="date" value={testedOn} max={today} onChange={(e) => setTestedOn(e.target.value)} className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
        {errors.testedOn && <p className="mt-1 text-sm font-medium text-bad">{errors.testedOn}</p>}
      </div>
      <fieldset>
        <legend className="mb-1 text-sm font-medium">Result</legend>
        <div className="flex gap-5 text-sm">
          <label className="flex items-center gap-2"><input type="radio" name="rt-result" checked={result === "PASSED"} onChange={() => setResult("PASSED")} /> Passed</label>
          <label className="flex items-center gap-2"><input type="radio" name="rt-result" checked={result === "FAILED"} onChange={() => setResult("FAILED")} /> Failed</label>
        </div>
        {errors.result && <p className="mt-1 text-sm font-medium text-bad">{errors.result}</p>}
      </fieldset>
      <div>
        <label htmlFor="rt-notes" className="mb-1 block text-sm font-medium">Notes (optional)</label>
        <textarea id="rt-notes" rows={3} maxLength={500} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Which machine, which backup, what you checked" className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
        {errors.notes && <p className="mt-1 text-sm font-medium text-bad">{errors.notes}</p>}
      </div>
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={busy}>Save</Button>
      </div>
    </form>
  );
}

/** A bar for how full the disk is, with the warning level marked. The figure is always written beside it. */
function DiskBar({ used, warn }: { used: number; warn: number }) {
  const colour = used >= 95 ? "bg-bad" : used >= warn ? "bg-warn" : "bg-ok";
  return (
    <div>
      <div role="img" aria-label={`${Math.round(used)} percent used; warning at ${warn} percent`} className="relative h-3 w-full overflow-hidden rounded-full bg-line">
        <span className={`block h-full ${colour}`} style={{ width: `${Math.min(100, used)}%` }} />
        <span aria-hidden className="absolute inset-y-0 w-0.5 bg-navy/70" style={{ left: `${warn}%` }} />
      </div>
      <p className="mt-1 flex justify-between text-xs text-muted"><span>{Math.round(used)}% used</span><span>Warning at {warn}%</span></p>
    </div>
  );
}

/** The disk the stored files are on, what the application uses, and what three years of reports are expected to need. */
export function StoragePanel({ health }: { health: StorageHealth }) {
  const used = health.diskTotalBytes - health.diskFreeBytes;
  return (
    <section aria-labelledby="storage-h" className="space-y-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <RuledHeading id="storage-h">Storage health</RuledHeading>
        <LevelBadge level={health.level} />
      </div>
      <Alert tone={ALERT[health.level]} title={health.headline} />
      <Card className="space-y-5 p-4 sm:p-5">
        {health.diskTotalBytes > 0 && <DiskBar used={health.diskUsedPercent} warn={health.warnPercent} />}
        <dl className="grid gap-x-8 gap-y-4 sm:grid-cols-2 lg:grid-cols-3">
          <Fact label="Where files are kept"><span className="font-mono text-[13px]">{health.path || "—"}</span></Fact>
          <Fact label="Disk size">{health.diskTotalBytes > 0 ? `${formatBytes(health.diskTotalBytes)} (${formatBytes(used)} used)` : "—"}</Fact>
          <Fact label="Free space">{health.diskTotalBytes > 0 ? formatBytes(health.diskFreeBytes) : "—"}</Fact>
          <Fact label="Issued reports">{health.reportCount} report{health.reportCount === 1 ? "" : "s"}, {formatBytes(health.reportBytes)}</Fact>
          <Fact label="Database">{formatBytes(health.databaseBytes)}</Fact>
          <Fact label="Average report">{formatBytes(health.averageReportBytes)} <span className="text-muted">({health.averageMeasured ? "measured" : "assumed"})</span></Fact>
          <Fact label={`Expected after ${health.retentionYears} years`}>
            {formatBytes(health.expectedBytes)} <span className="text-muted">for {health.activeFaculty} faculty</span>
          </Fact>
          <Fact label="Disk then, if nothing else grows">{health.diskTotalBytes > 0 ? `${Math.round(health.projectedPercent)}% full` : "—"}</Fact>
        </dl>
        <p className="border-t border-line pt-3 text-xs text-muted">{health.note}</p>
      </Card>
    </section>
  );
}

/**
 * The console's view of the two checks: nothing but a quiet line while they are healthy, a prominent banner for each that is
 * not. A backup that is overdue is the one thing on the console an administrator must not miss.
 */
export function HealthBanners({ backup, storage }: { backup: BackupHealth | null; storage: StorageHealth | null }) {
  const items: { key: string; name: string; health: { level: HealthLevel; headline: string } }[] = [];
  if (backup) items.push({ key: "backup", name: "Backups", health: backup });
  if (storage) items.push({ key: "storage", name: "Storage", health: storage });
  if (items.length === 0) return null;

  const trouble = items.filter((i) => i.health.level !== "ok");
  return (
    <div className="space-y-3">
      {trouble.map((i) => (
        <Alert
          key={i.key}
          tone={ALERT[i.health.level]}
          title={`${i.name}: ${i.health.headline}`}
          action={<a href="/admin/health" className={`inline-flex h-9 items-center rounded-lg px-3.5 text-sm font-semibold ${i.health.level === "problem" ? "bg-fill text-white" : "border border-line-strong bg-surface text-ink"}`}>Open System health</a>}
        />
      ))}
      {trouble.length === 0 && (
        <p className="flex flex-wrap items-center gap-3 text-sm text-muted">
          {items.map((i) => (
            <span key={i.key} className="inline-flex items-center gap-2">{i.name} <LevelBadge level={i.health.level} /></span>
          ))}
          <a href="/admin/health" className="font-medium text-brand hover:underline">System health</a>
        </p>
      )}
    </div>
  );
}
