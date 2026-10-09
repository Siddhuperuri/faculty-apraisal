"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ConsoleHeader, STAGE_COLOUR, StageBar, StatTile } from "@/components/console/parts";
import { HealthBanners } from "@/components/admin/HealthPanels";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { Alert, Button, Card, EmptyState, RuledHeading, Skeleton, linkButton } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { auditActionLabel, describeDetails } from "@/lib/adminLabels";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/labels";
import type { AdminOverview, BackupHealth, SetupCheck, StorageHealth } from "@/lib/types";

const LEVEL: Record<SetupCheck["level"], { mark: string; word: string; cls: string }> = {
  ok: { mark: "✓", word: "In order", cls: "text-ok" },
  warn: { mark: "!", word: "Worth attention", cls: "text-warn" },
  problem: { mark: "✕", word: "Needs fixing", cls: "text-bad" },
};

export default function AdminOverviewPage() {
  const [data, setData] = useState<AdminOverview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [backup, setBackup] = useState<BackupHealth | null>(null);
  const [storage, setStorage] = useState<StorageHealth | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      setData(await get<AdminOverview>("/api/admin/overview"));
      setError(null);
      stamp();
      // The health checks are an addition to the overview: if one cannot be read the overview still shows.
      const [b, s] = await Promise.allSettled([get<BackupHealth>("/api/admin/backup-health"), get<StorageHealth>("/api/admin/storage-health")]);
      setBackup(b.status === "fulfilled" ? b.value : null);
      setStorage(s.status === "fulfilled" ? s.value : null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, [stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void load();
  }, [load]);

  if (error) {
    return <Alert tone="error" title="Could not load the overview" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!data) {
    return (
      <div className="space-y-4" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-12 w-1/2" />
        <div className="grid gap-3 sm:grid-cols-4"><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /></div>
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const active = data.accounts.reduce((a, r) => a + r.active, 0);
  const disabled = data.accounts.reduce((a, r) => a + r.disabled, 0);
  const problems = data.checks.filter((c) => c.level === "problem").length;
  const warnings = data.checks.filter((c) => c.level === "warn").length;
  const p = data.pipeline;
  const n = (...keys: string[]) => keys.reduce((a, k) => a + (p[k] ?? 0), 0);
  const started = Object.entries(p).filter(([k]) => k !== "NOT_STARTED").reduce((a, [, v]) => a + v, 0);

  return (
    <div className="space-y-8">
      <ConsoleHeader eyebrow="Administration" title="Administrator console" aside={<RefreshButton onRefresh={load} loadedAt={loadedAt} />}>
        Accounts, set-up and activity. Administrators manage the system; they cannot read or change anyone&apos;s appraisal.
      </ConsoleHeader>

      <HealthBanners backup={backup} storage={storage} />

      {problems > 0 ? (
        <Alert tone="error" title={`${problems} set-up problem${problems === 1 ? "" : "s"} to fix`}>Faculty or reviewers are blocked until these are fixed. See the checks below.</Alert>
      ) : warnings > 0 ? (
        <Alert tone="warn" title="Set-up is working, with something worth attention">See the checks below.</Alert>
      ) : (
        <Alert tone="ok" title="Set-up is in order">Every check below passes.</Alert>
      )}

      <div className="rise grid gap-3 sm:grid-cols-2 lg:grid-cols-4" style={{ "--i": 1 } as React.CSSProperties}>
        <StatTile label="Active accounts" value={active} href="/admin/users" hint={`${disabled} disabled`} />
        <StatTile label="Not yet signed in" value={data.neverSignedIn} tone={data.neverSignedIn > 0 ? "warn" : "plain"} href="/admin/users" hint="Active accounts that have never been used" />
        <StatTile label="Academic year" value={data.year?.name ?? "None"} href="/admin/setup" hint="Where new appraisals start" />
        <StatTile label="Appraisals started" value={started} hint={`${p.NOT_STARTED ?? 0} faculty have not started`} />
      </div>

      <section aria-labelledby="checks-h" className="space-y-3">
        <RuledHeading id="checks-h">Set-up checks</RuledHeading>
        <ul className="divide-y divide-line rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
          {data.checks.map((c) => {
            const l = LEVEL[c.level];
            return (
              <li key={c.code} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
                <p className="flex items-start gap-3">
                  <span aria-hidden className={`mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 text-xs font-bold ${l.cls} border-current`}>{l.mark}</span>
                  <span>
                    <span className="sr-only">{l.word}: </span>
                    {c.message}
                  </span>
                </p>
                {c.link && c.level !== "ok" && <Link href={c.link} className="font-medium text-brand hover:underline">Fix this</Link>}
              </li>
            );
          })}
        </ul>
      </section>

      <div className="grid gap-8 lg:grid-cols-2">
        <section aria-labelledby="accts-h" className="space-y-3">
          <RuledHeading id="accts-h">Accounts by role</RuledHeading>
          <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
            <table className="w-full border-collapse text-left text-sm">
              <caption className="sr-only">Accounts by role</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Role</th>
                  <th scope="col" className="px-4 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">Active</th>
                  <th scope="col" className="px-4 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">Disabled</th>
                </tr>
              </thead>
              <tbody>
                {data.accounts.map((r) => (
                  <tr key={r.role} className="border-t border-line">
                    <th scope="row" className="px-4 py-2.5 text-left font-normal">{roleLabel(r.role)}</th>
                    <td className="px-4 py-2.5 text-right tabular-nums">{r.active}</td>
                    <td className="px-4 py-2.5 text-right tabular-nums text-muted">{r.disabled}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Link href="/admin/users" className={linkButton("secondary")}>Manage accounts</Link>
        </section>

        <section aria-labelledby="pipe-h" className="space-y-3">
          <RuledHeading id="pipe-h">Appraisals{data.year ? ` · ${data.year.name}` : ""}</RuledHeading>
          <Card className="space-y-3 p-4">
            <StageBar
              caption="Appraisals by stage"
              segments={[
                { key: "AP", label: "Approved", value: n("APPROVED"), className: STAGE_COLOUR.APPROVED },
                { key: "PR", label: "With the Principal / Director", value: n("HOD_APPROVED", "PRINCIPAL_REVIEW"), className: STAGE_COLOUR.ONWARD },
                { key: "HO", label: "With the HoD", value: n("SUBMITTED", "HOD_REVIEW"), className: STAGE_COLOUR.NEEDS_HOD },
                { key: "DR", label: "Drafts", value: n("DRAFT"), className: "bg-line-strong" },
                { key: "NS", label: "Not started", value: p.NOT_STARTED ?? 0, className: "bg-line" },
              ]}
            />
            <p className="text-xs text-muted">Counts only. Administrators never see who, or what is written in an appraisal.</p>
          </Card>
        </section>
      </div>

      <section aria-labelledby="recent-h" className="space-y-3">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <RuledHeading id="recent-h">Recent activity</RuledHeading>
          <Link href="/admin/audit" className="text-sm font-medium text-brand hover:underline">Open the full audit trail</Link>
        </div>
        {data.recent.length === 0 ? (
          <EmptyState>Nothing has been recorded yet.</EmptyState>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
            <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
              <caption className="sr-only">The latest entries in the audit trail</caption>
              <tbody>
                {data.recent.map((e) => (
                  <tr key={e.id} className="border-t border-line first:border-t-0 align-top">
                    <td className="whitespace-nowrap px-4 py-2.5 tabular-nums text-muted">{formatDateTime(e.at)}</td>
                    <td className="px-4 py-2.5">{e.actorEmail ?? <span className="text-muted">System</span>}</td>
                    <td className="px-4 py-2.5 font-medium">{auditActionLabel(e.action)}</td>
                    <td className="px-4 py-2.5 text-xs text-muted">{describeDetails(e.details)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
