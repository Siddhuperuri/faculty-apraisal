"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ConsoleHeader, STAGE_COLOUR, StageBar, StatTile, YearPicker } from "@/components/console/parts";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { Alert, Button, Card, EmptyState, RuledHeading, Skeleton, StatusBadge, linkButton } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { LEVELS } from "@/lib/hierarchy";
import type { PrincipalConsole } from "@/lib/types";

/**
 * The console of the Principal or the Director Technical, who stand at the same level and so are shown the same thing, for
 * the whole college: what the Heads of Department have forwarded and is waiting for a decision, what is final, and how many
 * are not with them yet (counted, never named).
 */
export function ApprovalConsolePage({ role }: { role: "PRINCIPAL" | "DIRECTOR" }) {
  const level = LEVELS[role];
  const below = LEVELS.HOD;
  const other = LEVELS[role === "PRINCIPAL" ? "DIRECTOR" : "PRINCIPAL"];
  const [data, setData] = useState<PrincipalConsole | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [yearId, setYearId] = useState<number | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      setData(await get<PrincipalConsole>(`/api${level.base}/console${yearId ? `?academicYearId=${yearId}` : ""}`));
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [level.base, yearId, stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load when the year changes
    void load();
  }, [load]);

  if (error) {
    return <Alert tone="error" title="Could not load your console" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!data) {
    return (
      <div className="space-y-4" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-12 w-1/2" />
        <div className="grid gap-3 sm:grid-cols-3"><Skeleton className="h-28" /><Skeleton className="h-28" /><Skeleton className="h-28" /></div>
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const t = data.totals;
  const departments = data.departments.filter((d) => d.faculty > 0);

  return (
    <div className="space-y-8">
      <ConsoleHeader
        eyebrow={level.title}
        title={`${level.title} console`}
        aside={<div className="flex flex-wrap items-end gap-3"><YearPicker year={data.year} years={data.years} onChange={setYearId} /><RefreshButton onRefresh={load} loadedAt={loadedAt} /></div>}
      >
        {t.faculty} faculty across {departments.length} department{departments.length === 1 ? "" : "s"}
      </ConsoleHeader>

      {t.awaiting > 0 ? (
        <Alert
          tone="warn"
          title={`${t.awaiting} appraisal${t.awaiting === 1 ? " is" : "s are"} waiting for your decision`}
          action={<Link href="/review" className={linkButton("primary")}>Open the review queue</Link>}
        >
          These have been approved and forwarded by a {below.title}. The longest-waiting are listed first below.
        </Alert>
      ) : (
        <Alert
          tone="ok"
          title="No appraisals need you today."
          action={<Link href="#dept-h" className={linkButton("secondary")}>See progress by department</Link>}
        >
          Every appraisal forwarded to you has been decided.
          {t.notYetWithYou > 0 && ` ${t.notYetWithYou} ${t.notYetWithYou === 1 ? "is" : "are"} still with a ${below.title} or not submitted; they reach you once approved.`}
        </Alert>
      )}

      <div className="rise grid gap-3 sm:grid-cols-3" style={{ "--i": 1 } as React.CSSProperties}>
        <StatTile label="Awaiting your decision" value={t.awaiting} tone={t.awaiting > 0 ? "action" : "plain"} href="/review" hint={`Forwarded by a ${below.title}`} />
        <StatTile label="Approved" value={t.approved} tone="ok" hint="Final, with the official report" />
        <StatTile label="Not yet with you" value={t.notYetWithYou} hint={`Not submitted, or still with the ${below.title}`} />
      </div>

      <section aria-labelledby="dept-h" className="space-y-3">
        <RuledHeading id="dept-h">By department</RuledHeading>
        {departments.length === 0 ? (
          <EmptyState>No faculty have been set up yet.</EmptyState>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
            <table className="w-full min-w-[46rem] border-collapse text-left text-sm">
              <caption className="sr-only">Appraisals by department</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  <th scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Department</th>
                  <th scope="col" className="px-3 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">Faculty</th>
                  <th scope="col" className="px-3 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">Awaiting you</th>
                  <th scope="col" className="px-3 py-2.5 text-right text-[11px] font-bold uppercase tracking-[0.1em]">Approved</th>
                  <th scope="col" className="w-56 px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">Progress</th>
                </tr>
              </thead>
              <tbody>
                {departments.map((d) => (
                  <tr key={d.id} className="border-t border-line align-middle">
                    <th scope="row" className="px-4 py-2.5 text-left font-normal">
                      <span className="font-display text-base font-medium">{d.name}</span>
                      <span className="block text-xs text-muted">{d.code}</span>
                    </th>
                    <td className="px-3 py-2.5 text-right tabular-nums">{d.faculty}</td>
                    <td className={`px-3 py-2.5 text-right tabular-nums ${d.awaiting > 0 ? "font-semibold text-navy" : ""}`}>{d.awaiting}</td>
                    <td className="px-3 py-2.5 text-right tabular-nums">{d.approved}</td>
                    <td className="px-4 py-2.5">
                      <StageBar
                        caption={d.code}
                        legend={false}
                        total={d.faculty}
                        segments={[
                          { key: "a", label: "Approved", value: d.approved, className: STAGE_COLOUR.APPROVED },
                          { key: "w", label: "Awaiting you", value: d.awaiting, className: STAGE_COLOUR.NEEDS_HOD },
                          { key: "n", label: "Not yet with you", value: d.notYetWithYou, className: STAGE_COLOUR.NOT_SUBMITTED },
                        ]}
                      />
                    </td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-navy bg-canvas font-semibold">
                  <th scope="row" className="px-4 py-2.5 text-left">College total</th>
                  <td className="px-3 py-2.5 text-right tabular-nums">{t.faculty}</td>
                  <td className="px-3 py-2.5 text-right tabular-nums">{t.awaiting}</td>
                  <td className="px-3 py-2.5 text-right tabular-nums">{t.approved}</td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
        <p className="text-xs text-muted">
          Appraisals reach you only after the {below.title} has approved and forwarded them, so earlier stages are counted together as &ldquo;not yet with you&rdquo;.
          The {other.title} sees the same appraisals, and either of you can decide them.
        </p>
      </section>

      <section aria-labelledby="wait-h" className="space-y-3">
        <RuledHeading id="wait-h">Awaiting your decision</RuledHeading>
        {data.awaitingList.length === 0 ? (
          <EmptyState action={<Link href="/review" className={linkButton("secondary")}>Open the review queue</Link>}>
            No appraisals need you today.
          </EmptyState>
        ) : (
          <>
          <ul className="grid gap-3 sm:hidden" aria-label={`Appraisals waiting for the ${level.title}`}>
            {data.awaitingList.map((a) => (
              <li key={a.appraisalId} className="rounded-xl border border-line bg-surface p-4 shadow-[var(--shadow-paper)]">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-display text-lg font-medium leading-snug text-navy">{a.name}</p>
                    <p className="text-xs text-muted">{a.employeeId}</p>
                  </div>
                  <StatusBadge status={a.status} />
                </div>
                <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-sm">
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Department</dt><dd className="mt-0.5">{a.department}</dd></div>
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Waiting since</dt><dd className="mt-0.5">{formatDateTime(a.updatedAt)}</dd></div>
                </dl>
                <Link href={`/appraisals/${a.appraisalId}`} className={`${linkButton("primary")} mt-4 w-full`}>Open appraisal<span className="sr-only"> for {a.name}</span></Link>
              </li>
            ))}
          </ul>
          <Card className="hidden overflow-x-auto sm:block">
            <table className="w-full min-w-[36rem] border-collapse text-left text-sm">
              <caption className="sr-only">Appraisals waiting for the {level.title}, longest waiting first</caption>
              <thead>
                <tr className="bg-brand-soft text-navy">
                  {["Faculty", "Department", "Status", "Waiting since", ""].map((h, i) => (
                    <th key={i} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h || <span className="sr-only">Open</span>}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.awaitingList.map((a) => (
                  <tr key={a.appraisalId} className="border-t border-line transition-colors hover:bg-warm/40">
                    <td className="px-4 py-2.5">
                      <span className="font-display text-base font-medium">{a.name}</span>
                      <span className="block text-xs text-muted">{a.employeeId}</span>
                    </td>
                    <td className="px-4 py-2.5">{a.department}</td>
                    <td className="px-4 py-2.5"><StatusBadge status={a.status} /></td>
                    <td className="px-4 py-2.5 text-muted">{formatDateTime(a.updatedAt)}</td>
                    <td className="px-4 py-2.5 text-right">
                      <Link href={`/appraisals/${a.appraisalId}`} className="font-medium text-brand hover:underline">Open<span className="sr-only"> {a.name}</span></Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          </>
        )}
        {t.awaiting > data.awaitingList.length && (
          <p className="text-sm text-muted">Showing the {data.awaitingList.length} longest-waiting of {t.awaiting}. <Link href="/review" className="font-medium text-brand hover:underline">Open the full review queue</Link>.</p>
        )}
      </section>
    </div>
  );
}
