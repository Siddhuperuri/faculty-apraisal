"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ConsoleHeader } from "@/components/console/parts";
import { Alert, Badge, Button, Card, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { ApiError, get, put } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import type { BackupHealth, HandoverNote } from "@/lib/types";

const RESTORE_STEPS = [
  "Find the most recent backup folder (it is named fams-backup- followed by the date and time) and copy it to the machine that will be restored.",
  "On that machine run  scripts\\restore.ps1 -From <backup folder>  without -Overwrite. It checks the backup and says what it would do; nothing is changed.",
  "Stop the backend service.",
  "Run  scripts\\restore.ps1 -From <backup folder> -Overwrite . It replaces the database, puts the stored files back into FAMS_STORAGE_DIR and checks them again.",
  "Start the backend, sign in as an administrator and check the Overview: accounts, appraisals and the audit trail should be as they were at the time of the backup.",
  "Record what you did under System health, as a restore test.",
];

const INPUT = "block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm";

export default function HandoverPage() {
  const [notes, setNotes] = useState<HandoverNote[] | null>(null);
  const [backup, setBackup] = useState<BackupHealth | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      const [n, b] = await Promise.all([get<HandoverNote[]>("/api/admin/handover"), get<BackupHealth>("/api/admin/backup-health")]);
      setNotes(n);
      setBackup(b);
      setDraft(Object.fromEntries(n.map((x) => [x.key, x.value])));
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

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setSaved(false);
    setProblems({});
    try {
      const n = await put<HandoverNote[]>("/api/admin/handover", draft);
      setNotes(n);
      setDraft(Object.fromEntries(n.map((x) => [x.key, x.value])));
      setSaved(true);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setProblems(err.fieldErrors);
      else setError(err instanceof ApiError ? err.message : "Could not save. Please try again.");
    } finally {
      setSaving(false);
    }
  };

  if (error && !notes) {
    return <Alert tone="error" title="Could not load the handover page" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!notes || !backup) {
    return <div className="space-y-4" aria-busy="true" aria-label="Loading"><Skeleton className="h-12 w-1/2" /><Skeleton className="h-64 w-full" /></div>;
  }

  const verified = backup.lastRestoreTest;
  const missing = notes.filter((n) => n.value === "").length;

  return (
    <div className="print-sheet space-y-8">
      <ConsoleHeader
        eyebrow="Administration"
        title="Administrator handover"
        aside={
          <div className="no-print flex flex-wrap items-end gap-3">
            <RefreshButton onRefresh={load} loadedAt={loadedAt} />
            <Link href="/admin/checklist" className="inline-flex min-h-9 items-center rounded-full border border-line-strong bg-surface px-3.5 text-sm font-semibold text-ink hover:bg-brand-soft">Checklists</Link>
            <Button size="sm" onClick={() => window.print()}>Print</Button>
          </div>
        }
      >
        What the next administrator needs on their first day, and where to look when something goes wrong.
      </ConsoleHeader>

      <section aria-labelledby="who-h" className="space-y-3">
        <RuledHeading id="who-h">People and places</RuledHeading>
        {missing > 0 && <Alert tone="warn" title={`${missing} of ${notes.length} not filled in yet`}>Fill these in now: they are what someone will need when you are away and the server stops.</Alert>}
        <Card className="p-4 sm:p-5">
          <form onSubmit={save} className="space-y-4" noValidate>
            {saved && <Alert tone="ok" title="Saved">The notes are kept with the rest of the college&apos;s data.</Alert>}
            {error && <Alert tone="error" title="Not saved">{error}</Alert>}
            {notes.map((n) => (
              <div key={n.key}>
                <label htmlFor={`note-${n.key}`} className="mb-1 block text-sm font-medium">{n.question}</label>
                <textarea
                  id={`note-${n.key}`}
                  rows={n.key === "other" ? 4 : 2}
                  maxLength={1000}
                  value={draft[n.key] ?? ""}
                  onChange={(e) => { setDraft((d) => ({ ...d, [n.key]: e.target.value })); setSaved(false); }}
                  className={`${INPUT}`}
                  placeholder="Not recorded yet"
                />
                {problems[n.key] && <p className="mt-1 text-sm font-medium text-bad">{problems[n.key]}</p>}
                {n.updatedBy && <p className="mt-0.5 text-xs text-muted">Last changed by {n.updatedBy}, {formatDateTime(n.updatedAt)}</p>}
              </div>
            ))}
            <p className="text-xs text-muted">Names, offices and telephone numbers only. Never write a password, key or token here: this page is kept in the database and is read by every administrator.</p>
            <div className="no-print"><Button type="submit" loading={saving}>Save</Button></div>
          </form>
        </Card>
      </section>

      <section aria-labelledby="where-h" className="space-y-3">
        <RuledHeading id="where-h">Where the backups are</RuledHeading>
        <Card className="p-4 sm:p-5">
          <dl className="grid gap-x-8 gap-y-4 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-[11px] font-bold uppercase tracking-[0.12em] text-muted">Backup folder</dt>
              <dd className="mt-0.5 break-words font-mono text-[13px]">{backup.destination || <span className="font-sans text-warn">Not set (FAMS_BACKUP_DIR)</span>}</dd>
            </div>
            <div>
              <dt className="text-[11px] font-bold uppercase tracking-[0.12em] text-muted">Last backup</dt>
              <dd className="mt-0.5">{backup.lastBackupAt ? formatDateTime(backup.lastBackupAt) : <span className="text-bad">None yet</span>}</dd>
            </div>
            <div>
              <dt className="text-[11px] font-bold uppercase tracking-[0.12em] text-muted">Last verified restore</dt>
              <dd className="mt-0.5">
                {verified ? <>{formatDate(verified.testedOn)} <span className="text-muted">by {verified.recordedBy}</span></> : <span className="text-warn">No restore has been tested</span>}
              </dd>
            </div>
            <div>
              <dt className="text-[11px] font-bold uppercase tracking-[0.12em] text-muted">Status</dt>
              <dd className="mt-0.5"><Badge tone={backup.level === "ok" ? "ok" : backup.level === "warn" ? "warn" : "bad"}>{backup.level === "ok" ? "Healthy" : backup.level === "warn" ? "Needs attention" : "Problem"}</Badge></dd>
            </div>
          </dl>
        </Card>
      </section>

      <section aria-labelledby="restore-h" className="space-y-3">
        <RuledHeading id="restore-h">How to restore from a backup</RuledHeading>
        <Card className="p-4 sm:p-5">
          <ol className="list-decimal space-y-2 pl-5 text-sm leading-relaxed">
            {RESTORE_STEPS.map((s) => <li key={s}>{s}</li>)}
          </ol>
          <p className="mt-3 text-xs text-muted">The restore script needs a database account that may create tables and triggers (SPRING_FLYWAY_USER and SPRING_FLYWAY_PASSWORD). Try the whole thing on a spare machine before you need it: see docs\deployment.md.</p>
        </Card>
      </section>

      <section aria-labelledby="routine-h" className="space-y-3">
        <RuledHeading id="routine-h">Routine</RuledHeading>
        <Card className="p-4 sm:p-5">
          <ul className="list-disc space-y-1.5 pl-5 text-sm leading-relaxed">
            <li><strong>Every day:</strong> the nightly backup runs. If System health turns red, run <span className="font-mono">scripts\backup.ps1</span> by hand and find out why.</li>
            <li><strong>Every month:</strong> look at System health (disk space) and Account review (inactive and duplicate accounts).</li>
            <li><strong>Every year:</strong> follow the end-of-year checklist, and test a restore.</li>
            <li><strong>When someone forgets their password:</strong> reset it on the Accounts page; they choose a new one at sign-in. Each reset is recorded in the audit trail.</li>
            <li><strong>When someone leaves:</strong> disable the account (never delete it: their appraisals stay on record).</li>
          </ul>
        </Card>
      </section>
    </div>
  );
}
