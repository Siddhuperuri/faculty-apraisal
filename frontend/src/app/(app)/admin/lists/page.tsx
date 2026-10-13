"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, Badge, Button, Card, RuledHeading, Skeleton } from "@/components/ui/primitives";
import { ApiError, get, post, put } from "@/lib/api";
import { inSequence, moveLabel, moveState } from "@/lib/sequence";
import type { ReferenceList, ReferenceValue } from "@/lib/types";

const INPUT = "block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm";

/**
 * Administration of the dropdown lists. Values are added, renamed, put in order (one place at a time with the Up and Down
 * arrows, saved at once) and switched off here; they are never deleted, so a record that holds one keeps its wording. A value the application's own rules depend on is marked
 * "used by rules": its wording can change, but it stays on.
 */
export default function ListsPage() {
  const [lists, setLists] = useState<ReferenceList[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<string>("");

  const load = useCallback(async () => {
    try {
      const data = await get<ReferenceList[]>("/api/admin/lists");
      setLists(data);
      setChosen((cur) => cur || data[0]?.code || "");
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data load
    void load();
  }, [load]);

  const list = useMemo(() => lists?.find((l) => l.code === chosen) ?? null, [lists, chosen]);

  if (error && !lists) {
    return (
      <Alert tone="error" title="Could not load the lists" action={<Button variant="secondary" size="sm" onClick={load}>Try again</Button>}>
        {error}
      </Alert>
    );
  }
  if (!lists) return <Skeleton className="h-64 w-full" />;

  return (
    <div className="space-y-6">
      <header>
        <h1 className="font-display text-2xl font-medium text-navy">Dropdown lists</h1>
        <p className="mt-1 max-w-3xl text-sm text-muted">
          The choices offered on the appraisal form. A new value is available to everyone at once. A value you switch off is no
          longer offered, but appraisals that already hold it keep it and its wording. Values are never deleted.
        </p>
      </header>

      <div className="grid gap-6 lg:grid-cols-[18rem_1fr]">
        <nav aria-label="Lists" className="space-y-1">
          {lists.map((l) => (
            <button
              key={l.code}
              type="button"
              onClick={() => setChosen(l.code)}
              aria-current={l.code === chosen ? "true" : undefined}
              className={`block w-full rounded-sm px-3 py-2 text-left text-sm ${l.code === chosen ? "bg-brand-soft font-semibold text-navy" : "hover:bg-warm/50"}`}
            >
              {l.name}
              <span className="ml-2 text-xs text-muted">{l.values.filter((v) => v.active).length}</span>
            </button>
          ))}
        </nav>

        {list && <ListEditor key={list.code} list={list} parents={lists.find((l) => l.code === list.parentList) ?? null} onChanged={load} />}
      </div>
    </div>
  );
}

function ListEditor({ list, parents, onChanged }: { list: ReferenceList; parents: ReferenceList | null; onChanged: () => Promise<void> }) {
  const grouped = list.parentList !== null;
  const [form, setForm] = useState({ code: "", label: "", reportLabel: "", parent: "" });
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setProblems({});
    setMessage(null);
    try {
      await post(`/api/admin/lists/${list.code}/values`, {
        code: form.code,
        label: form.label,
        reportLabel: form.reportLabel || null,
        parent: grouped ? form.parent : undefined,
      });
      setForm({ code: "", label: "", reportLabel: "", parent: form.parent });
      setMessage("Added. It is available on the form now.");
      await onChanged();
    } catch (err) {
      const apiErr = err as ApiError;
      setProblems(apiErr.fieldErrors ?? {});
      setMessage(apiErr.fieldErrors ? null : apiErr.message);
    } finally {
      setSaving(false);
    }
  };

  const values = inSequence(list.values);
  const th = "px-3 py-2 text-[11px] font-bold uppercase tracking-wide";

  return (
    <div className="space-y-6">
      <RuledHeading>{list.name}</RuledHeading>
      {list.description && <p className="text-sm text-muted">{list.description}</p>}

      <div className="overflow-x-auto rounded-md border border-line-strong/70">
        <table className="w-full min-w-[40rem] border-collapse text-left text-sm">
          <caption className="sr-only">Values of {list.name}</caption>
          <thead>
            <tr className="bg-brand-soft text-navy">
              {grouped && <th scope="col" className={th}>{parents?.name ?? "Belongs to"}</th>}
              <th scope="col" className={th}>Code</th>
              <th scope="col" className={th}>Wording on the form</th>
              {list.kind !== "FREE" && <th scope="col" className={th}>Wording on the report</th>}
              <th scope="col" className={`${th} w-28`}>Sequence</th>
              <th scope="col" className={`${th} text-right`}>Status</th>
            </tr>
          </thead>
          <tbody>
            {values.map((v) => (
              <ValueRow
                key={v.id}
                value={v}
                movement={moveState(list.values, v)}
                showParent={grouped}
                showReport={list.kind !== "FREE"}
                renamable={list.kind !== "FREE"}
                onChanged={onChanged}
              />
            ))}
          </tbody>
        </table>
      </div>

      <Card className="p-4">
        <form onSubmit={add} className="space-y-3" noValidate>
          <h3 className="font-display text-lg font-medium text-navy">Add a value</h3>
          <div className="grid gap-3 md:grid-cols-2">
            {grouped && (
              <label className="block text-sm font-semibold">
                {parents?.name ?? "Belongs to"}
                <select className={INPUT} value={form.parent} onChange={(e) => setForm({ ...form, parent: e.target.value })}>
                  <option value="">Select…</option>
                  {[...new Map((parents?.values ?? []).filter((p) => p.active).map((p) => [p.code, p])).values()].map((p) => (
                    <option key={p.code} value={p.code}>{p.label}</option>
                  ))}
                </select>
                {problems.parent && <span className="mt-1 block text-sm font-medium text-bad">{problems.parent}</span>}
              </label>
            )}
            {list.kind !== "FREE" && (
              <label className="block text-sm font-semibold">
                Code {list.kind === "NUMBER" ? "(a whole number)" : "(capital letters, digits and underscores)"}
                <input className={INPUT} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} autoComplete="off" />
                {problems.code && <span className="mt-1 block text-sm font-medium text-bad">{problems.code}</span>}
              </label>
            )}
            <label className="block text-sm font-semibold">
              {list.kind === "FREE" ? "Suggested wording" : "Wording on the form"}
              <input className={INPUT} value={form.label} onChange={(e) => setForm({ ...form, label: e.target.value })} autoComplete="off" />
              {problems.label && <span className="mt-1 block text-sm font-medium text-bad">{problems.label}</span>}
            </label>
            {list.kind !== "FREE" && (
              <label className="block text-sm font-semibold">
                Wording on the report <span className="font-normal text-muted">(optional; the same as the form when empty)</span>
                <input className={INPUT} value={form.reportLabel} onChange={(e) => setForm({ ...form, reportLabel: e.target.value })} autoComplete="off" />
                {problems.reportLabel && <span className="mt-1 block text-sm font-medium text-bad">{problems.reportLabel}</span>}
              </label>
            )}
          </div>
          {message && <p role="status" className="text-sm font-medium">{message}</p>}
          <Button type="submit" loading={saving}>Add value</Button>
        </form>
      </Card>
    </div>
  );
}

function ValueRow({ value, movement, showParent, showReport, renamable, onChanged }: {
  value: ReferenceValue;
  movement: { canUp: boolean; canDown: boolean };
  showParent: boolean;
  showReport: boolean;
  renamable: boolean;
  onChanged: () => Promise<void>;
}) {
  const [label, setLabel] = useState(value.label);
  const [report, setReport] = useState(value.reportLabel ?? "");
  const [busy, setBusy] = useState(false);
  const [moving, setMoving] = useState<"UP" | "DOWN" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const dirty = label !== value.label || report !== (value.reportLabel ?? "");

  const send = async (body: Record<string, unknown>) => {
    setBusy(true);
    setProblem(null);
    try {
      await put(`/api/admin/lists/values/${value.id}`, body);
      await onChanged();
    } catch (err) {
      const apiErr = err as ApiError;
      setProblem(Object.values(apiErr.fieldErrors ?? {})[0] ?? apiErr.message);
    } finally {
      setBusy(false);
    }
  };

  // Moves one place and saves at once; the list is then read again, so the page shows what the database holds.
  const move = async (direction: "UP" | "DOWN") => {
    setMoving(direction);
    setProblem(null);
    try {
      await post(`/api/admin/lists/values/${value.id}/move`, { direction });
      await onChanged();
    } catch (err) {
      const apiErr = err as ApiError;
      setProblem(Object.values(apiErr.fieldErrors ?? {})[0] ?? apiErr.message);
    } finally {
      setMoving(null);
    }
  };

  const small = "block w-full rounded-sm border border-line-strong bg-surface px-2 py-1 text-sm";
  return (
    <tr className={`border-t border-line align-top ${value.active ? "" : "bg-canvas/70 text-muted"}`}>
      {showParent && <td className="px-3 py-2">{value.parent}</td>}
      <td className="px-3 py-2 font-mono text-xs">{value.code}</td>
      <td className="px-3 py-2">
        {renamable ? (
          <input aria-label={`Wording of ${value.code}`} className={small} value={label} onChange={(e) => setLabel(e.target.value)} />
        ) : (
          value.label
        )}
        {problem && <p className="mt-1 text-xs font-medium text-bad">{problem}</p>}
      </td>
      {showReport && (
        <td className="px-3 py-2">
          <input aria-label={`Report wording of ${value.code}`} className={small} value={report} placeholder={value.label} onChange={(e) => setReport(e.target.value)} />
        </td>
      )}
      <td className="whitespace-nowrap px-3 py-2">
        <span className="inline-flex gap-1">
          {(["UP", "DOWN"] as const).map((direction) => {
            const label = moveLabel(value, direction);
            const allowed = direction === "UP" ? movement.canUp : movement.canDown;
            return (
              <Button
                key={direction}
                size="sm"
                variant="secondary"
                aria-label={label}
                title={label}
                disabled={!allowed || moving !== null || busy}
                loading={moving === direction}
                onClick={() => void move(direction)}
              >
                <span aria-hidden>{direction === "UP" ? "↑" : "↓"}</span>
              </Button>
            );
          })}
        </span>
      </td>
      <td className="whitespace-nowrap px-3 py-2 text-right">
        {dirty && (
          <Button
            size="sm"
            loading={busy}
            onClick={() => send({ label: renamable ? label : undefined, reportLabel: report || null })}
          >
            Save
          </Button>
        )}{" "}
        {value.locked ? (
          <Badge tone="info">used by rules</Badge>
        ) : (
          <Button size="sm" variant="ghost" loading={busy} onClick={() => send({ active: !value.active })}>
            {value.active ? "Switch off" : "Switch on"}
          </Button>
        )}
        {!value.active && <Badge tone="warn">off</Badge>}
      </td>
    </tr>
  );
}
