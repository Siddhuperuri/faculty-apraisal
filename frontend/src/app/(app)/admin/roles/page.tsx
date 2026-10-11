"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ChoiceInput } from "@/components/ui/FieldInput";
import { Alert, Badge, Button, Card, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { ApiError, get, put } from "@/lib/api";
import type { AcademicYear, AdminReference, RoleMarks } from "@/lib/types";

/**
 * The institutional roles a faculty member can record under Teaching & Learning (Head of the Department, Associate Dean ...)
 * and the marks each carries in the scoring policy of an academic year. The roles themselves are a dropdown list (add,
 * rename or switch one off under Dropdown lists); a role without marks earns nothing until its marks are set here.
 */
export default function RolesPage() {
  const [years, setYears] = useState<AcademicYear[] | null>(null);
  const [yearId, setYearId] = useState<string>("");
  const [roles, setRoles] = useState<RoleMarks[] | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    get<AdminReference>("/api/admin/reference")
      .then((r) => {
        setYears(r.academicYears);
        setYearId(String((r.academicYears.find((y) => y.active) ?? r.academicYears[0])?.id ?? ""));
      })
      .catch((e) => setError((e as Error).message));
  }, []);

  const load = useCallback(async (id: string) => {
    if (!id) return;
    try {
      const list = await get<RoleMarks[]>(`/api/admin/role-marks?academicYearId=${id}`);
      setRoles(list);
      setDraft(Object.fromEntries(list.map((r) => [r.code, String(r.marks)])));
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- loads again when the year is changed
    void load(yearId);
  }, [load, yearId]);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setProblems({});
    setSaved(false);
    try {
      const marks = Object.fromEntries(Object.entries(draft).map(([k, v]) => [k, Number(v.trim())]));
      const list = await put<RoleMarks[]>("/api/admin/role-marks", { academicYearId: Number(yearId), marks });
      setRoles(list);
      setDraft(Object.fromEntries(list.map((r) => [r.code, String(r.marks)])));
      setSaved(true);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setProblems(err.fieldErrors);
      else setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (error && !roles) return <Alert tone="error" title="Could not load the roles">{error}</Alert>;
  if (!years || !roles) return <Skeleton className="h-64 w-full" />;

  const dirty = roles.some((r) => draft[r.code] !== String(r.marks));
  const invalid = Object.values(draft).some((v) => !/^\d{1,3}$/.test(v.trim()));

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="font-display text-3xl font-medium text-navy">Institutional roles</h1>
          <p className="mt-1 max-w-2xl text-sm text-muted">
            Marks a faculty member earns for holding a role, in Teaching &amp; Learning (within its maximum). The marks apply to the
            current scoring policy of every cadre for the year; appraisals already approved keep the policy they began with. A role
            you add under <Link href="/admin/lists" className="underline">Dropdown lists</Link> earns 0 until you set its marks here.
          </p>
        </div>
        <div className="w-56">
          <ChoiceInput
            label="Academic year"
            value={yearId}
            options={years.map((y) => ({ value: String(y.id), label: `${y.name}${y.active ? " (open)" : ""}` }))}
            onChange={setYearId}
            required
          />
        </div>
      </header>

      <Card className="p-5">
        <form onSubmit={save} className="space-y-4" noValidate>
          {saved && <Alert tone="info" title="Saved">The marks apply to appraisals from now on.</Alert>}
          {error && <Alert tone="error" title="Not saved">{error}</Alert>}
          <table className="w-full border-collapse text-left text-sm">
            <caption className="sr-only">Marks for each institutional role</caption>
            <thead>
              <tr className="bg-brand-soft text-navy">
                <th scope="col" className="px-3 py-2 text-[11px] font-bold uppercase tracking-wide">Role</th>
                <th scope="col" className="w-40 px-3 py-2 text-right text-[11px] font-bold uppercase tracking-wide">Marks</th>
              </tr>
            </thead>
            <tbody>
              {roles.map((r) => (
                <tr key={r.code} className="border-t border-line">
                  <th scope="row" className="px-3 py-2 text-left font-normal">
                    {r.label} {!r.active && <Badge tone="warn">switched off</Badge>}
                  </th>
                  <td className="px-3 py-2 text-right">
                    <input
                      aria-label={`Marks for ${r.label}`}
                      inputMode="numeric"
                      value={draft[r.code] ?? ""}
                      onChange={(e) => setDraft((d) => ({ ...d, [r.code]: e.target.value }))}
                      className="w-24 rounded-sm border border-line-strong bg-surface px-2 py-1 text-right tabular-nums"
                    />
                    {problems[`marks.${r.code}`] && <p className="mt-1 text-xs font-medium text-bad">{problems[`marks.${r.code}`]}</p>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {roles.length === 0 && <p className="text-sm text-muted">There are no institutional roles yet.</p>}
          <div className="flex justify-end">
            <Button type="submit" loading={busy} disabled={!dirty || invalid}>Save marks</Button>
          </div>
        </form>
      </Card>
      <RuledHeading>How the marks are used</RuledHeading>
      <p className="text-sm text-muted">
        A faculty member lists the roles they held on the Teaching &amp; Learning page. Each earns the marks above, added to the
        marks for their courses, and the total can never be more than the criterion&apos;s maximum for their cadre.
      </p>
    </div>
  );
}
