"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ConsoleHeader, STAGE_COLOUR, StageBar, StatTile, YearPicker } from "@/components/console/parts";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { Alert, Badge, Button, Card, EmptyState, RuledHeading, Skeleton, StatusBadge, linkButton } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { STAGE_LABEL } from "@/lib/labels";
import { scrollToId } from "@/lib/focus";
import { SORT_LABEL, VIEW_LABEL, VIEW_ORDER, filterRoster, viewOf, type RosterSort, type RosterView } from "@/lib/roster";
import type { HodConsole, Stage } from "@/lib/types";

export default function HodConsolePage() {
  const [data, setData] = useState<HodConsole | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [yearId, setYearId] = useState<number | null>(null);
  const [view, setView] = useState<RosterView>("");
  const [query, setQuery] = useState("");
  const [dept, setDept] = useState("");
  const [cadre, setCadre] = useState("");
  const [sort, setSort] = useState<RosterSort>("");
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      setData(await get<HodConsole>(`/api/hod/console${yearId ? `?academicYearId=${yearId}` : ""}`));
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [yearId, stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- load when the year changes
    void load();
  }, [load]);

  const shown = useMemo(() => filterRoster(data?.roster ?? [], { view, department: dept, cadre, query }, sort), [data, view, dept, cadre, query, sort]);
  const viewCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const r of data?.roster ?? []) counts[viewOf(r)] = (counts[viewOf(r)] ?? 0) + 1;
    return counts;
  }, [data]);
  const cadres = useMemo(() => [...new Set((data?.roster ?? []).map((r) => r.cadre))].sort(), [data]);
  const narrowed = view !== "" || dept !== "" || cadre !== "" || query.trim() !== "";
  const clearFilters = () => {
    setView("");
    setDept("");
    setCadre("");
    setQuery("");
    setSort("");
  };

  if (error) {
    return <Alert tone="error" title="Could not load your console" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
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

  const t = data.totals;
  // An appraisal with a query raised is with the HoD but waiting for the faculty member, so it is not waiting for the HoD.
  const awaitingFaculty = data.roster.filter((r) => r.stage === "NEEDS_HOD" && r.queryRaised).length;
  const needsAction = t.NEEDS_HOD - awaitingFaculty;
  const faculty = data.roster.length;
  const submitted = faculty - t.NOT_SUBMITTED;
  // A progress card, or the "who has not submitted" shortcut, narrows the roster below and takes the reader to it.
  const chooseDepartment = (code: string) => {
    const next = dept === code ? "" : code;
    setDept(next);
    if (next) scrollToId("roster-h");
  };
  const showRoster = (to: RosterView) => {
    setView(to);
    scrollToId("roster-h");
  };
  const codes = data.departments.map((d) => d.code).join(", ");

  return (
    <div className="space-y-8">
      <ConsoleHeader
        title={data.departments.length === 0 ? "HoD Console" : `${codes} - HoD Console`}
        aside={<div className="flex flex-wrap items-end gap-3"><YearPicker year={data.year} years={data.years} onChange={setYearId} /><RefreshButton onRefresh={load} loadedAt={loadedAt} /></div>}
      >
        {data.departments.length === 0 ? "No department is assigned to you yet." : `${faculty} faculty`}
      </ConsoleHeader>

      {data.departments.length === 0 ? (
        <Alert tone="warn" title="No department assigned">An administrator needs to assign your department before faculty appraisals can reach you.</Alert>
      ) : (
        <>
          {needsAction > 0 ? (
            <Alert
              tone="warn"
              title={`${needsAction} appraisal${needsAction === 1 ? " is" : "s are"} waiting for your review`}
              action={<Link href="/review" className={linkButton("primary")}>Open the review queue</Link>}
            >
              Faculty are waiting for your approval. The people concerned are listed first below.
              {awaitingFaculty > 0 && ` ${awaitingFaculty} more ${awaitingFaculty === 1 ? "is" : "are"} waiting for the faculty member to meet you.`}
            </Alert>
          ) : awaitingFaculty > 0 ? null : (
            <Alert
              tone="ok"
              title="No appraisals need you today."
              action={
                t.NOT_SUBMITTED > 0
                  ? <Button variant="secondary" onClick={() => showRoster("NOT_SUBMITTED")}>See who has not submitted ({t.NOT_SUBMITTED})</Button>
                  : <Button variant="secondary" onClick={() => showRoster("APPROVED")}>See the approved appraisals</Button>
              }
            >
              {t.NOT_SUBMITTED > 0
                ? "Everything submitted has been dealt with. Some faculty have not submitted yet."
                : "Every appraisal in your department has been submitted and dealt with."}
            </Alert>
          )}

          <div className="rise grid gap-3 sm:grid-cols-2 lg:grid-cols-4" style={{ "--i": 1 } as React.CSSProperties}>
            <StatTile label="Needs your action" value={needsAction} tone={needsAction > 0 ? "action" : "plain"} href="/review" hint={awaitingFaculty > 0 ? `Submitted, or under your review; ${awaitingFaculty} more with a query raised` : "Submitted, or under your review"} />
            <StatTile label="With the Principal / Director" value={t.ONWARD} hint="You approved and forwarded these" />
            <StatTile label="Approved" value={t.APPROVED} tone="ok" hint="Final, with the official report" />
            <StatTile label="Not yet submitted" value={t.NOT_SUBMITTED} hint="Not started, or still a draft" />
          </div>

          <section aria-labelledby="progress-h" className="space-y-3">
            <RuledHeading id="progress-h">Progress by department</RuledHeading>
            <div className="grid gap-4 lg:grid-cols-2">
              {data.departments.map((d) => (
                <Card key={d.id} className={`relative space-y-3 p-4 transition ${dept === d.code ? "ring-2 ring-navy" : "hover:-translate-y-px hover:shadow-[var(--shadow-lift)]"}`}>
                  <div className="flex items-baseline justify-between gap-3">
                    <h3 className="font-display text-lg font-medium text-navy">
                      {/* The button's empty overlay makes the whole card the click target, so the numbers below stay readable text. */}
                      <button type="button" aria-pressed={dept === d.code} onClick={() => chooseDepartment(d.code)} className="text-left after:absolute after:inset-0 after:rounded-xl after:content-[''] focus-visible:after:ring-2 focus-visible:after:ring-brand">
                        {d.name}
                      </button>
                    </h3>
                    <span className="text-xs text-muted">{d.code} · {d.faculty} faculty</span>
                  </div>
                  <StageBar
                    caption={d.code}
                    total={d.faculty}
                    segments={(["APPROVED", "ONWARD", "NEEDS_HOD", "NOT_SUBMITTED"] as Stage[]).map((s) => ({
                      key: s,
                      label: STAGE_LABEL[s],
                      value: d.counts[s],
                      className: STAGE_COLOUR[s],
                    }))}
                  />
                  <div className="relative z-10 flex flex-wrap items-center justify-between gap-2 border-t border-line pt-2 text-xs">
                    <span className="font-semibold text-brand">{dept === d.code ? "Showing these faculty below. Click again to show everyone." : "Click to show these faculty below."}</span>
                    <Link href={`/review?department=${encodeURIComponent(d.name)}`} className="font-medium text-brand hover:underline">Open in the review queue<span className="sr-only"> for {d.name}</span></Link>
                  </div>
                </Card>
              ))}
            </div>
            <p className="text-xs text-muted">{submitted} of {faculty} faculty have submitted for {data.year?.name ?? "this year"}.</p>
          </section>

          <section aria-labelledby="roster-h" className="space-y-3">
            <RuledHeading id="roster-h">Faculty</RuledHeading>
            <div className="flex flex-wrap items-end gap-3">
              <div>
                <label htmlFor="roster-search" className="mb-1 block text-sm font-medium">Search</label>
                <input id="roster-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name, employee ID or cadre" className="w-72 max-w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
              </div>
              {data.departments.length > 1 && (
                <div>
                  <label htmlFor="roster-dept" className="mb-1 block text-sm font-medium">Department</label>
                  <select id="roster-dept" value={dept} onChange={(e) => setDept(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
                    <option value="">All departments</option>
                    {data.departments.map((d) => <option key={d.id} value={d.code}>{d.code}</option>)}
                  </select>
                </div>
              )}
              <div>
                <label htmlFor="roster-stage" className="mb-1 block text-sm font-medium">Show</label>
                <select id="roster-stage" value={view} onChange={(e) => setView(e.target.value as RosterView)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
                  <option value="">Everyone ({faculty})</option>
                  {VIEW_ORDER.map((v) => <option key={v} value={v}>{VIEW_LABEL[v]} ({viewCounts[v] ?? 0})</option>)}
                </select>
              </div>
              {cadres.length > 1 && (
                <div>
                  <label htmlFor="roster-cadre" className="mb-1 block text-sm font-medium">Designation</label>
                  <select id="roster-cadre" value={cadre} onChange={(e) => setCadre(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
                    <option value="">All designations</option>
                    {cadres.map((c) => <option key={c} value={c}>{c}</option>)}
                  </select>
                </div>
              )}
              <div>
                <label htmlFor="roster-sort" className="mb-1 block text-sm font-medium">Sort by</label>
                <select id="roster-sort" value={sort} onChange={(e) => setSort(e.target.value as RosterSort)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
                  <option value="">Needs action first</option>
                  {(Object.keys(SORT_LABEL) as Exclude<RosterSort, "">[]).map((k) => <option key={k} value={k}>{SORT_LABEL[k]}</option>)}
                </select>
              </div>
              <p className="pb-2 text-sm text-muted" role="status">{narrowed ? `Showing ${shown.length} of ${faculty}` : `${faculty} faculty`}</p>
              {(narrowed || sort) && <Button variant="ghost" size="sm" onClick={clearFilters}>Clear filters</Button>}
            </div>

            {shown.length === 0 ? (
              <EmptyState action={faculty > 0 ? <Button variant="secondary" onClick={clearFilters}>Clear filters</Button> : undefined}>{faculty === 0 ? "No faculty are assigned to your department yet." : "No one matches these filters."}</EmptyState>
            ) : (
              <>
              <ul className="grid gap-3 sm:hidden" aria-label="Faculty of your department and where each appraisal stands">
                {shown.map((r) => (
                  <li key={r.employeeId} className="rounded-xl border border-line bg-surface p-4 shadow-[var(--shadow-paper)]">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div>
                        <p className="font-display text-lg font-medium leading-snug text-navy">{r.name}</p>
                        <p className="text-xs text-muted">{r.employeeId}</p>
                      </div>
                      {r.status ? <StatusBadge status={r.status} queryRaised={r.queryRaised} /> : <Badge>Not submitted</Badge>}
                    </div>
                    {r.stage === "NEEDS_HOD" && !r.queryRaised && <p className="mt-3"><Badge tone="warn">Needs your action</Badge></p>}
                    {r.queryRaised && <p className="mt-3"><Badge>Awaiting the faculty member</Badge></p>}
                    <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-sm">
                      <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Department</dt><dd className="mt-0.5">{r.department}</dd></div>
                      <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Cadre</dt><dd className="mt-0.5">{r.cadre}</dd></div>
                      <div className="col-span-2"><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Submitted</dt><dd className="mt-0.5">{r.submittedAt ? formatDateTime(r.submittedAt) : "—"}</dd></div>
                    </dl>
                    {r.appraisalId != null && <Link href={`/appraisals/${r.appraisalId}`} className={`${linkButton("secondary")} mt-4 w-full`}>Open appraisal<span className="sr-only"> for {r.name}</span></Link>}
                  </li>
                ))}
              </ul>
              <div className="hidden overflow-x-auto rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:block">
                <table className="w-full min-w-[46rem] border-collapse text-left text-sm">
                  <caption className="sr-only">Faculty of your department and where each appraisal stands</caption>
                  <thead>
                    <tr className="bg-brand-soft text-navy">
                      {["Faculty", "Department", "Cadre", "Where it stands", "Submitted", ""].map((h, i) => (
                        <th key={i} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">{h || <span className="sr-only">Open</span>}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {shown.map((r) => (
                      <tr key={`${r.employeeId}`} className="border-t border-line transition-colors hover:bg-warm/40">
                        <td className="px-4 py-2.5">
                          <span className="font-display text-base font-medium">{r.name}</span>
                          <span className="block text-xs text-muted">{r.employeeId}</span>
                        </td>
                        <td className="px-4 py-2.5">{r.department}</td>
                        <td className="px-4 py-2.5">{r.cadre}</td>
                        <td className="px-4 py-2.5">
                          <div className="flex flex-wrap items-center gap-2">
                            {r.status ? <StatusBadge status={r.status} queryRaised={r.queryRaised} /> : <span className="text-muted">Not yet submitted</span>}
                            {r.stage === "NEEDS_HOD" && !r.queryRaised && <Badge tone="warn">Needs your action</Badge>}
                            {r.queryRaised && <Badge>Awaiting the faculty member</Badge>}
                          </div>
                        </td>
                        <td className="px-4 py-2.5 text-muted">{r.submittedAt ? formatDateTime(r.submittedAt) : "—"}</td>
                        <td className="px-4 py-2.5 text-right">
                          {r.appraisalId != null && (
                            <Link href={`/appraisals/${r.appraisalId}`} className="font-medium text-brand hover:underline">Open<span className="sr-only"> {r.name}</span></Link>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              </>
            )}
            <p className="text-xs text-muted">A draft is private to the faculty member until they submit it, so it is shown as not yet submitted.</p>
          </section>
        </>
      )}
    </div>
  );
}
