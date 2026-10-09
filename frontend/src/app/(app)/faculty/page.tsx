"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { HistoryTimeline } from "@/components/appraisal/HistoryTimeline";
import { MessagesFromHod } from "@/components/appraisal/Messages";
import { ReportDownload } from "@/components/appraisal/ReportDownload";
import { StatTile } from "@/components/console/parts";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { Alert, Button, Card, PageRuler, RuledHeading, Skeleton, StatusBadge, linkButton } from "@/components/ui/primitives";
import { ApiError, get, post } from "@/lib/api";
import { formatDateTime, formatNumber } from "@/lib/format";
import { JOURNEY, JOURNEY_STEP } from "@/lib/hierarchy";
import { statusLine } from "@/lib/labels";
import type { AppraisalView, ListItem, Status } from "@/lib/types";

function greeting(): string {
  const h = new Date().getHours();
  return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
}

const delay = (i: number) => ({ "--i": i }) as React.CSSProperties;

function Journey({ status }: { status: Status }) {
  const at = JOURNEY_STEP[status];
  return (
    <ol className="grid grid-cols-3 gap-x-2 gap-y-3 sm:grid-cols-5" aria-label="Where your appraisal is">
      {JOURNEY.map((label, i) => {
        const done = i < at || status === "APPROVED";
        const here = i === at && status !== "APPROVED";
        return (
          <li key={label} aria-current={here ? "step" : undefined} className="text-center">
            <span
              className={`mx-auto flex h-8 w-8 items-center justify-center rounded-full border-2 text-xs font-bold ${
                done ? "border-ok bg-ok text-surface" : here ? "border-navy bg-fill text-white" : "border-line-strong text-muted"
              }`}
            >
              {done ? "\u2713" : i + 1}
            </span>
            <span className={`mt-1 block text-[11px] leading-tight ${here ? "font-semibold text-ink" : "text-muted"}`}>
              {label}
            </span>
          </li>
        );
      })}
    </ol>
  );
}

export default function FacultyDashboard() {
  const { user } = useAuth();
  const router = useRouter();
  const [items, setItems] = useState<ListItem[] | null>(null);
  const [counts, setCounts] = useState<Record<string, number> | null>(null);
  const [view, setView] = useState<AppraisalView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);
  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    try {
      const list = await get<ListItem[]>("/api/appraisals");
      setItems(list);
      setError(null);
      stamp();
      if (list[0]) {
        // The extras are a convenience: if one fails the dashboard still works without it.
        const [c, v] = await Promise.allSettled([
          get<Record<string, number>>(`/api/appraisals/${list[0].id}/sections`),
          get<AppraisalView>(`/api/appraisals/${list[0].id}`),
        ]);
        if (c.status === "fulfilled") setCounts(c.value);
        if (v.status === "fulfilled") setView(v.value);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }, [stamp]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void load();
  }, [load]);

  const start = async () => {
    setStarting(true);
    setStartError(null);
    try {
      const { id } = await post<{ id: number }>("/api/appraisals");
      router.push(`/appraisals/${id}/general`);
    } catch (e) {
      setStartError(e instanceof ApiError ? e.message : "Could not start the appraisal.");
      setStarting(false);
    }
  };

  if (error) {
    return <Alert tone="error" title="Could not load your dashboard" action={<Button variant="secondary" size="sm" onClick={() => void load()}>Try again</Button>}>{error}</Alert>;
  }
  if (!items) {
    return (
      <div className="space-y-4" aria-busy="true">
        <Skeleton className="h-10 w-1/2" />
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  const current = items[0];
  const editable = current && current.status === "DRAFT";
  // A criterion whose maximum is 0 for this cadre does not apply, so it is not "still to do".
  const applicable = view ? view.scores.filter((r) => r.maxMarks === null || r.maxMarks > 0).length : 0;
  const entered = view ? view.scores.filter((r) => (r.maxMarks === null || r.maxMarks > 0) && r.score != null).length : 0;
  const scoreTotal = view ? view.scores.reduce((a, r) => a + (r.score ?? 0), 0) : 0;
  const maxTotal = view ? view.scores.reduce((a, r) => a + (r.maxMarks ?? 0), 0) : 0;
  const hasUnlimited = view ? view.scores.some((r) => r.maxMarks === null) : false;

  return (
    <div className="space-y-8">
      <div className="rise flex flex-wrap items-end justify-between gap-4" style={delay(0)}>
       <div>
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Faculty console</p>
        <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy sm:text-5xl">
          {greeting()}
          {current ? <span className="italic">, {current.facultyName}</span> : ""}
        </h1>
        <p className="mt-1 text-sm text-muted">{user?.email}</p>
       </div>
       <RefreshButton onRefresh={load} loadedAt={loadedAt} />
      </div>

      {current && current.status !== "DRAFT" && (
        <div className="rise" style={delay(1)}>
          <MessagesFromHod appraisalId={current.id} prominent />
        </div>
      )}

      {!current ? (
        <Card className="rise max-w-3xl p-7" >
          <div style={delay(1)}>
            <h2 className="font-display text-2xl font-semibold text-navy">Start your appraisal</h2>
            <p className="mt-2 max-w-2xl text-[15px] text-muted">
              Your Faculty Self Appraisal &amp; Assessment Report for the current academic year is completed section by section.
              Your entries save automatically, and you can come back at any time before submitting. Once submitted it goes to your
              Head of the Department and then to the Principal or the Director Technical, and can no longer be changed.
            </p>
            {startError && <div className="mt-4"><Alert tone="error" title="Could not start">{startError}</Alert></div>}
            <div className="mt-5">
              <Button onClick={() => void start()} loading={starting} className="h-11 px-5">Start this year&apos;s appraisal</Button>
            </div>
          </div>
        </Card>
      ) : (
        <div className="rise overflow-hidden rounded-lg border border-line bg-surface shadow-[var(--shadow-lift)] sm:grid sm:grid-cols-[14rem_minmax(0,1fr)]" style={delay(1)}>
          {/* The spine: academic year in large serif numerals */}
          <div
            className="flex flex-col justify-between gap-6 bg-fill px-6 py-6 text-white"
            style={{ backgroundImage: "repeating-linear-gradient(135deg, rgb(255 255 255 / 0.05) 0 1px, transparent 1px 12px)" }}
          >
            <p className="text-[11px] font-semibold uppercase tracking-[0.26em] text-ochre">Academic year</p>
            <p className="whitespace-nowrap font-display text-4xl font-medium leading-none tracking-tight" aria-label={`Academic year ${current.academicYear}`}>
              {current.academicYear.split("-")[0]}
              <span className="text-white/70">–{current.academicYear.split("-")[1]}</span>
            </p>
          </div>

          <div className="space-y-5 p-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="text-xs font-semibold uppercase tracking-[0.2em] text-muted">Your appraisal</p>
                <p className="mt-1 max-w-md font-display text-xl leading-snug text-ink">{statusLine(current.status, current.queryRaised, true)}</p>
              </div>
              <StatusBadge status={current.status} queryRaised={current.queryRaised} />
            </div>

            <PageRuler counts={counts} />

            <dl className="grid gap-4 border-t border-line pt-4 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs uppercase tracking-[0.14em] text-muted">Last updated</dt>
                <dd className="mt-0.5 font-medium">{formatDateTime(current.updatedAt)}</dd>
              </div>
              <div>
                <dt className="text-xs uppercase tracking-[0.14em] text-muted">Submitted</dt>
                <dd className="mt-0.5 font-medium">{current.submittedAt ? formatDateTime(current.submittedAt) : "Not yet"}</dd>
              </div>
            </dl>

            <div className="flex flex-wrap gap-2">
              <Link href={editable ? `/appraisals/${current.id}/general` : `/appraisals/${current.id}`} className={linkButton("primary")}>
                {editable ? "Continue appraisal" : "View appraisal"}
              </Link>
              <Link href={`/appraisals/${current.id}`} className={linkButton("secondary")}>Score &amp; review</Link>
            </div>
          </div>
        </div>
      )}

      {current && (
        <section aria-labelledby="journey-h" className="rise space-y-3" style={delay(2)}>
          <RuledHeading id="journey-h">Where your appraisal is</RuledHeading>
          <Card className="p-4 sm:p-5">
            <Journey status={current.status} />
          </Card>
        </section>
      )}

      {current && view && (
        <section aria-labelledby="progress-h" className="rise space-y-3" style={delay(3)}>
          <RuledHeading id="progress-h">Your progress</RuledHeading>
          <div className="grid gap-3 sm:grid-cols-2">
            <StatTile
              label="Criteria scored"
              value={`${entered} of ${applicable}`}
              href={`/appraisals/${current.id}`}
              tone={entered < applicable && editable ? "warn" : "plain"}
              hint={entered < applicable ? "Criteria still without a score" : "Every criterion has a score"}
            />
            <StatTile
              label="Self-score total"
              value={formatNumber(scoreTotal)}
              href={`/appraisals/${current.id}`}
              hint={hasUnlimited ? `B1 to B4 are out of ${formatNumber(maxTotal)} for your cadre; B5 to B9 have no maximum` : `Out of a maximum of ${formatNumber(maxTotal)} for your cadre`}
            />
          </div>
        </section>
      )}

      {current && (
        <section aria-labelledby="report-h" className="rise" style={delay(4)}>
          <Card className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
            <div className="max-w-xl">
              <h2 id="report-h" className="font-display text-lg font-medium text-navy">The printed form</h2>
              <p className="text-sm text-muted">
                {current.status === "APPROVED"
                  ? "Your appraisal is approved. The official report is ready, and is the same every time you download it."
                  : "A draft of the college\u2019s form with everything entered so far, marked DRAFT. The official copy is issued once the appraisal is approved."}
              </p>
            </div>
            <ReportDownload appraisalId={current.id} status={current.status} variant={current.status === "APPROVED" ? "primary" : "secondary"} />
          </Card>
        </section>
      )}

      {current && view && view.history.length > 0 && (
        <section aria-labelledby="hist-h" className="rise space-y-3" style={delay(5)}>
          <RuledHeading id="hist-h">What has happened</RuledHeading>
          <Card className="p-4 sm:p-5">
            <HistoryTimeline history={view.history} forFaculty />
          </Card>
        </section>
      )}

      {items.length > 1 && (
        <section aria-labelledby="history" className="rise" style={delay(2)}>
          <h2 id="history" className="mb-2 border-b border-navy pb-1.5 font-display text-lg font-semibold text-navy">Earlier appraisals</h2>
          <ul className="divide-y divide-line rounded-lg border border-line bg-surface shadow-[var(--shadow-paper)]">
            {items.slice(1).map((a) => (
              <li key={a.id} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                <Link href={`/appraisals/${a.id}`} className="font-display text-base font-medium text-brand hover:underline">{a.academicYear}</Link>
                <StatusBadge status={a.status} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
