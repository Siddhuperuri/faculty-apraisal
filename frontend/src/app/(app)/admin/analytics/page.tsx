"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { CATEGORY_COLOURS, DonutChart, PieChart, STATUS_COLOUR } from "@/components/admin/Charts";
import { StatTile } from "@/components/console/parts";
import { ChoiceInput } from "@/components/ui/FieldInput";
import { Alert, Badge, Button, Card, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { STATUS_INFO, roleLabel } from "@/lib/labels";
import type { Analytics } from "@/lib/types";
import { NO_FILTERS, filterGroups, noneLabel, optionsFor, tally, type Dimension, type UserFilters } from "@/lib/userGroups";

const STATUSES = ["DRAFT", "SUBMITTED", "HOD_REVIEW", "HOD_APPROVED", "PRINCIPAL_REVIEW", "APPROVED"] as const;

const DIMENSIONS: { value: Dimension; label: string }[] = [
  { value: "role", label: "Role" },
  { value: "status", label: "Account status" },
  { value: "department", label: "Department" },
  { value: "designation", label: "Designation" },
];

/** The words for one value of a dimension: a role's name, "Active", or the department or designation as it is. */
function wording(by: Dimension, key: string): string {
  if (key === "") return noneLabel(by);
  if (by === "role") return roleLabel(key as never);
  if (by === "status") return key === "ACTIVE" ? "Active" : "Disabled";
  return key;
}

/**
 * Appraisal progress and user counts for one academic year, and only counts: no name, e-mail address or appraisal content is
 * part of this page. The pie chart groups the accounts by one thing and narrows them with the filters above it.
 */
export default function AnalyticsPage() {
  const [data, setData] = useState<Analytics | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [year, setYear] = useState<string>("");
  const [by, setBy] = useState<Dimension>("role");
  const [filters, setFilters] = useState<UserFilters>(NO_FILTERS);

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

  const groups = useMemo(() => data?.userGroups ?? [], [data]);
  const narrowed = useMemo(() => filterGroups(groups, filters), [groups, filters]);
  const slices = useMemo(
    () => tally(narrowed, by).map((t, i) => ({ label: wording(by, t.key), value: t.value, color: CATEGORY_COLOURS[i % CATEGORY_COLOURS.length] })),
    [narrowed, by],
  );
  const totalUsers = slices.reduce((a, s) => a + s.value, 0);
  const allUsers = groups.reduce((a, g) => a + g.count, 0);
  const departments = useMemo(() => optionsFor(groups, "department"), [groups]);
  const designations = useMemo(() => optionsFor(groups, "designation"), [groups]);
  const setFilter = (name: keyof UserFilters) => (v: string) => setFilters((f) => ({ ...f, [name]: v }));

  if (error) {
    return <Alert tone="error" title="Could not load the analytics" action={<Button variant="secondary" size="sm" onClick={() => void load(year)}>Try again</Button>}>{error}</Alert>;
  }
  if (!data) return <Skeleton className="h-96 w-full" />;

  const t = data.totals;
  // a department with no faculty and nothing started says nothing, so it is left out
  const activeDepartments = data.departments.filter((d) => d.faculty > 0 || d.started > 0);
  const statusSlices = STATUSES.map((s) => ({ label: STATUS_INFO[s].label, value: data.byStatus[s] ?? 0, color: STATUS_COLOUR[s] }));
  const filtered = Object.values(filters).some((v) => v !== "");

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-medium text-navy">Analytics</h1>
          <p className="mt-1 text-sm text-muted">Counts only. No name or appraisal is shown here.</p>
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
        <StatTile label="Faculty" value={t.faculty} hint="active faculty" />
        <StatTile label="Submitted" value={t.submitted} hint={`${t.submissionRate}% of faculty`} />
        <StatTile label="Approved" value={t.approved} tone="ok" hint={`${t.approvalRate}% of submitted`} />
        <StatTile label="Not started" value={t.notStarted} hint="no appraisal yet" />
      </section>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)]">
        <Card className="space-y-4 p-5">
          <RuledHeading>Appraisals by status</RuledHeading>
          <DonutChart slices={statusSlices} centre={String(t.started)} label={`Appraisals of ${data.year.name} by status`} />
        </Card>

        <Card className="space-y-4 p-5">
          <RuledHeading>Departments</RuledHeading>
          {activeDepartments.length === 0 ? (
            <p className="text-sm text-muted">No department has any faculty yet.</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[30rem] border-collapse text-left text-sm">
                <caption className="sr-only">Appraisal progress for each department</caption>
                <thead>
                  <tr className="text-muted">
                    <th scope="col" className="px-2 py-2 text-[11px] font-bold uppercase tracking-wide">Department</th>
                    <th scope="col" className="px-2 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Faculty</th>
                    <th scope="col" className="px-2 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Submitted</th>
                    <th scope="col" className="px-2 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Approved</th>
                    <th scope="col" className="w-36 px-2 py-2 text-[11px] font-bold uppercase tracking-wide">Submitted share</th>
                  </tr>
                </thead>
                <tbody>
                  {activeDepartments.map((d) => (
                    <tr key={d.id} className="border-t border-line">
                      <th scope="row" className="px-2 py-2 text-left font-medium">
                        {d.code}
                        {d.headsOfDepartment === 0 && d.faculty > 0 && <span className="ml-2 align-middle"><Badge tone="warn">No HoD</Badge></span>}
                      </th>
                      <td className="px-2 py-2 text-right tabular-nums">{d.faculty}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{d.submitted}</td>
                      <td className="px-2 py-2 text-right tabular-nums">{d.approved}</td>
                      <td className="px-2 py-2">
                        <div className="flex items-center gap-2">
                          <div className="h-2 flex-1 overflow-hidden rounded-full bg-canvas" role="img" aria-label={`${d.submissionRate}% submitted`}>
                            <div className="h-full rounded-full bg-brand transition-[width] duration-500" style={{ width: `${Math.min(100, d.submissionRate)}%` }} />
                          </div>
                          <span className="w-12 text-right tabular-nums text-muted">{d.submissionRate}%</span>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>

      <Card className="space-y-5 p-5">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <RuledHeading>Users</RuledHeading>
          <p className="text-sm text-muted" role="status">
            {filtered ? `${totalUsers} of ${allUsers} users` : `${allUsers} users`}
            {data.neverSignedIn > 0 && ` · ${data.neverSignedIn} not signed in yet`}
          </p>
        </div>

        <div role="group" aria-label="Choose what the pie chart shows" className="flex flex-wrap items-end gap-3">
          <Pick id="users-by" label="Group by" value={by} onChange={(v) => { setBy(v as Dimension); setFilters((f) => ({ ...f, [v]: "" })); }} all={null}
            options={DIMENSIONS.map((d) => ({ value: d.value, label: d.label }))} />
          {by !== "role" && (
            <Pick id="users-role" label="Role" value={filters.role} onChange={setFilter("role")} all="All roles"
              options={optionsFor(groups, "role").map((r) => ({ value: r, label: roleLabel(r as never) }))} />
          )}
          {by !== "status" && (
            <Pick id="users-status" label="Status" value={filters.status} onChange={setFilter("status")} all="Any status"
              options={[{ value: "ACTIVE", label: "Active" }, { value: "DISABLED", label: "Disabled" }]} />
          )}
          {by !== "department" && departments.length > 0 && (
            <Pick id="users-dept" label="Department" value={filters.department} onChange={setFilter("department")} all="All departments"
              options={departments.map((d) => ({ value: d, label: d }))} />
          )}
          {by !== "designation" && designations.length > 0 && (
            <Pick id="users-desig" label="Designation" value={filters.designation} onChange={setFilter("designation")} all="All designations"
              options={designations.map((d) => ({ value: d, label: d }))} />
          )}
          {filtered && <Button variant="ghost" size="sm" onClick={() => setFilters(NO_FILTERS)}>Clear filters</Button>}
        </div>

        <PieChart slices={slices} total={totalUsers} label={`Users grouped by ${DIMENSIONS.find((d) => d.value === by)?.label.toLowerCase()}`} />
      </Card>
    </div>
  );
}

function Pick({ id, label, value, onChange, all, options }: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  all: string | null;
  options: { value: string; label: string }[];
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium">{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className="rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm">
        {all !== null && <option value="">{all}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}
