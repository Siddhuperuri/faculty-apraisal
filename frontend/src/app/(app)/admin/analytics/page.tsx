"use client";

import { useCallback, useEffect, useState } from "react";
import { DonutChart, Legend, STATUS_COLOUR, StackedBars } from "@/components/admin/Charts";
import { StatTile } from "@/components/console/parts";
import { ChoiceInput } from "@/components/ui/FieldInput";
import { Alert, Button, Card, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { STATUS_INFO, roleLabel } from "@/lib/labels";
import type { Analytics } from "@/lib/types";

const STATUSES = ["DRAFT", "SUBMITTED", "HOD_REVIEW", "HOD_APPROVED", "PRINCIPAL_REVIEW", "APPROVED"] as const;

/**
 * Appraisal progress and user counts for one academic year: department by department, never combined, and only counts. No
 * name, e-mail address or appraisal content is part of this page.
 */
export default function AnalyticsPage() {
  const [data, setData] = useState<Analytics | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [year, setYear] = useState<string>("");

  const load = useCallback(async (yearId: string) => {
    try {
      setData(await get<Analytics>(`/api/admin/analytics${yearId ? `?academicYearId=${yearId}` : ""}`));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- loads again when the year is changed
    void load(year);
  }, [load, year]);

  if (error) {
    return <Alert tone="error" title="Could not load the analytics" action={<Button variant="secondary" size="sm" onClick={() => void load(year)}>Try again</Button>}>{error}</Alert>;
  }
  if (!data) return <Skeleton className="h-96 w-full" />;

  const t = data.totals;
  const statusSlices = STATUSES.map((s) => ({ label: STATUS_INFO[s].label, value: data.byStatus[s] ?? 0, color: STATUS_COLOUR[s] }));
  const maxDept = Math.max(1, ...data.departments.map((d) => Math.max(d.started, d.faculty)));

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-medium text-navy">Analytics</h1>
          <p className="mt-1 max-w-2xl text-sm text-muted">
            How far the academic year has got, by department. These are counts only; no one&apos;s name or appraisal is shown here.
          </p>
        </div>
        <div className="w-56">
          <ChoiceInput
            label="Academic year"
            value={year || String(data.year.id)}
            options={data.years.map((y) => ({ value: String(y.id), label: `${y.name}${y.active ? " (open)" : ""}` }))}
            onChange={(v) => setYear(v)}
            required
          />
        </div>
      </header>

      <section aria-label="Summary" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Faculty (active)" value={t.faculty} hint={`${t.notStarted} not started`} />
        <StatTile label="Appraisals started" value={t.started} hint={`${t.submitted} submitted`} />
        <StatTile label="Submission rate" value={`${t.submissionRate}%`} hint="submitted of all faculty" />
        <StatTile label="Approval rate" value={`${t.approvalRate}%`} hint={`${t.approved} approved of ${t.submitted} submitted`} />
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card className="space-y-4 p-5">
          <RuledHeading>Appraisals by status</RuledHeading>
          <DonutChart slices={statusSlices} centre={String(t.started)} label={`Appraisals of ${data.year.name} by status`} />
        </Card>

        <Card className="space-y-4 p-5">
          <RuledHeading>Submitted and approved</RuledHeading>
          <DonutChart
            label="Faculty by progress"
            centre={`${t.submissionRate}%`}
            slices={[
              { label: "Approved", value: t.approved, color: STATUS_COLOUR.APPROVED },
              { label: "Submitted, not yet approved", value: Math.max(0, t.submitted - t.approved), color: STATUS_COLOUR.HOD_REVIEW },
              { label: "Started, not submitted", value: Math.max(0, t.started - t.submitted), color: STATUS_COLOUR.DRAFT },
              { label: "Not started", value: t.notStarted, color: "#d7dbe3" },
            ]}
          />
        </Card>
      </div>

      <Card className="space-y-4 p-5">
        <RuledHeading>Department by department</RuledHeading>
        {data.departments.length === 0 ? (
          <p className="text-sm text-muted">No department has any faculty yet.</p>
        ) : (
          <>
            <Legend items={STATUSES.map((s) => ({ label: STATUS_INFO[s].label, color: STATUS_COLOUR[s] }))} />
            <StackedBars
              label="Appraisals by status for each department"
              max={maxDept}
              rows={data.departments.map((d) => ({
                label: d.code,
                note: `${d.started} of ${d.faculty}`,
                segments: STATUSES.map((s) => ({ label: STATUS_INFO[s].label, value: d.byStatus[s] ?? 0, color: STATUS_COLOUR[s] })),
              }))}
            />
            <div className="overflow-x-auto">
              <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
                <caption className="sr-only">Figures for each department</caption>
                <thead>
                  <tr className="bg-brand-soft text-navy">
                    <th scope="col" className="px-3 py-2 text-[11px] font-bold uppercase tracking-wide">Department</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Faculty</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">HoDs</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Started</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Submitted</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Approved</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Submission rate</th>
                    <th scope="col" className="px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Approval rate</th>
                  </tr>
                </thead>
                <tbody>
                  {data.departments.map((d) => (
                    <tr key={d.id} className="border-t border-line">
                      <th scope="row" className="px-3 py-2 text-left font-medium">{d.code} <span className="font-normal text-muted">{d.name}{d.active ? "" : " (closed)"}</span></th>
                      <td className="px-3 py-2 text-right tabular-nums">{d.faculty}</td>
                      <td className={`px-3 py-2 text-right tabular-nums ${d.headsOfDepartment === 0 && d.faculty > 0 ? "font-semibold text-bad" : ""}`}>{d.headsOfDepartment}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{d.started}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{d.submitted}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{d.approved}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{d.submissionRate}%</td>
                      <td className="px-3 py-2 text-right tabular-nums">{d.approvalRate}%</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </Card>

      <Card className="space-y-4 p-5">
        <RuledHeading>Users</RuledHeading>
        <StackedBars
          label="Users by role"
          rows={data.users.map((u) => ({
            label: roleLabel(u.role as never),
            note: `${u.active} active${u.disabled > 0 ? `, ${u.disabled} disabled` : ""}`,
            segments: [
              { label: "Active", value: u.active, color: "#2f6fdb" },
              { label: "Disabled", value: u.disabled, color: "#c9ced8" },
            ],
          }))}
        />
        <p className="text-sm text-muted">{data.neverSignedIn} active user{data.neverSignedIn === 1 ? " has" : "s have"} not signed in yet.</p>
      </Card>
    </div>
  );
}
