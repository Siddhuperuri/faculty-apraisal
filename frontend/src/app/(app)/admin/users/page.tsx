"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { ImportAccountsDialog } from "@/components/admin/ImportAccountsDialog";
import { OneTimePasswordDialog } from "@/components/admin/OneTimePassword";
import { UserFormDialog } from "@/components/admin/UserFormDialog";
import { useAuth } from "@/components/auth/AuthProvider";
import { Alert, Badge, Button, EmptyState, Skeleton } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { RefreshButton, useLoadedAt } from "@/components/ui/RefreshButton";
import { get, post, put } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { ROLE_LABEL, isWithdrawnRole, roleLabel } from "@/lib/labels";
import type { AdminReference, AdminUserRow, IssuedPassword, Role } from "@/lib/types";

type Pending = { kind: "disable" | "reset"; user: AdminUserRow } | null;

/** Dean and Vice Principal accounts were closed when those levels were withdrawn. They stay listed for the record. */
const WITHDRAWN_NOTE = "This role has been withdrawn. The account is closed and kept for the record.";

type Sort = "" | "name" | "recent" | "oldest" | "role";
const SORTS: { value: Sort; label: string }[] = [
  { value: "", label: "Order added" },
  { value: "name", label: "Name A to Z" },
  { value: "recent", label: "Last sign-in, newest first" },
  { value: "oldest", label: "Last sign-in, longest ago first" },
  { value: "role", label: "Role" },
];
const ROLE_ORDER: Record<string, number> = { ADMIN: 0, PRINCIPAL: 1, DIRECTOR: 2, HOD: 3, FACULTY: 4 };

export default function UsersPage() {
  const { user: me } = useAuth();
  const [reference, setReference] = useState<AdminReference | null>(null);
  const [rows, setRows] = useState<AdminUserRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [role, setRole] = useState("");
  // These narrow the loaded list in the browser (the search and role are asked of the server).
  const [status, setStatus] = useState("");
  const [department, setDepartment] = useState("");
  const [cadre, setCadre] = useState("");
  const [signIn, setSignIn] = useState("");
  const [sort, setSort] = useState<Sort>("");

  const [form, setForm] = useState<{ userId?: number } | null>(null);
  const [importing, setImporting] = useState(false);
  const [issued, setIssued] = useState<{ value: IssuedPassword; reason: "created" | "reset" } | null>(null);
  const [pending, setPending] = useState<Pending>(null);
  const [working, setWorking] = useState(false);

  const [loadedAt, stamp] = useLoadedAt();

  const load = useCallback(async () => {
    const params = new URLSearchParams();
    if (query.trim()) params.set("q", query.trim());
    if (role) params.set("role", role);
    try {
      setRows(await get<AdminUserRow[]>(`/api/admin/users?${params}`));
      setError(null);
      stamp();
    } catch (e) {
      setError((e as Error).message);
    }
  }, [query, role, stamp]);

  useEffect(() => {
    get<AdminReference>("/api/admin/reference").then(setReference).catch((e) => setError((e as Error).message));
  }, []);

  // Searching waits a moment after typing stops, so each keystroke is not a request.
  useEffect(() => {
    const t = setTimeout(() => void load(), 250);
    return () => clearTimeout(t);
  }, [load]);

  const run = async (action: () => Promise<void>) => {
    setWorking(true);
    setNotice(null);
    try {
      await action();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setWorking(false);
      setPending(null);
    }
  };

  const confirm = () =>
    pending &&
    run(async () => {
      if (pending.kind === "disable") {
        await put(`/api/admin/users/${pending.user.id}`, { status: "DISABLED" });
        setNotice(`${pending.user.email} was disabled and signed out.`);
        await load();
      } else {
        const value = await post<IssuedPassword>(`/api/admin/users/${pending.user.id}/reset-password`);
        setIssued({ value, reason: "reset" });
        await load();
      }
    });

  const enable = (u: AdminUserRow) =>
    run(async () => {
      await put(`/api/admin/users/${u.id}`, { status: "ACTIVE" });
      setNotice(`${u.email} can sign in again.`);
      await load();
    });

  const shown = useMemo(() => {
    if (!rows || !reference) return [];
    const dept = reference.departments.find((d) => String(d.id) === department);
    const out = rows.filter((u) => {
      if (status && u.status !== status) return false;
      if (dept && !(u.role === "FACULTY" ? u.department === dept.name : (u.hodDepartments ?? "").split(", ").includes(dept.code))) return false;
      if (cadre && u.cadre !== cadre) return false;
      if (signIn === "never" && u.lastLoginAt) return false;
      if (signIn === "pending" && !u.mustChangePassword) return false;
      if (signIn === "active" && !u.lastLoginAt) return false;
      return true;
    });
    const label = (u: AdminUserRow) => (u.name ?? u.email).toLowerCase();
    const time = (u: AdminUserRow) => (u.lastLoginAt ? Date.parse(u.lastLoginAt) : null);
    if (sort === "name") out.sort((a, b) => label(a).localeCompare(label(b)));
    if (sort === "role") out.sort((a, b) => (ROLE_ORDER[a.role] ?? 9) - (ROLE_ORDER[b.role] ?? 9) || label(a).localeCompare(label(b)));
    if (sort === "recent" || sort === "oldest") {
      // An account that has never signed in counts as the longest ago: first when sorting oldest first, last otherwise.
      out.sort((x, y) => (sort === "recent" ? (time(y) ?? 0) - (time(x) ?? 0) : (time(x) ?? 0) - (time(y) ?? 0)));
    }
    return out;
  }, [rows, reference, status, department, cadre, signIn, sort]);
  const narrowed = status !== "" || department !== "" || cadre !== "" || signIn !== "";
  const clearAll = () => {
    setQuery("");
    setRole("");
    setStatus("");
    setDepartment("");
    setCadre("");
    setSignIn("");
    setSort("");
  };

  if (!reference || !rows) {
    return error ? (
      <Alert tone="error" title="Could not load accounts" action={<Button variant="secondary" size="sm" onClick={() => window.location.reload()}>Reload</Button>}>{error}</Alert>
    ) : (
      <div className="space-y-3" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-10 w-1/3" />
        <Skeleton className="h-56 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="rise flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Administration</p>
          <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">Accounts</h1>
          <p className="text-sm text-muted">Create sign-ins for faculty, Heads of Department, the Principal, the Director Technical and administrators, one at a time or from a CSV file.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <RefreshButton onRefresh={load} loadedAt={loadedAt} />
          <Button variant="secondary" onClick={() => setImporting(true)}>Add accounts from a file</Button>
          <Button onClick={() => setForm({})}>Add an account</Button>
        </div>
      </div>

      {error && <Alert tone="error" title="Problem">{error}</Alert>}
      {notice && <Alert tone="ok" title="Done">{notice}</Alert>}

      <div role="search" aria-label="Filter accounts" className="space-y-3 rounded-xl border border-line bg-surface/70 p-3 sm:p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[14rem] flex-1">
            <label htmlFor="user-search" className="mb-1 block text-sm font-medium">Search</label>
            <input id="user-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Name, e-mail or employee ID" className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-sm" />
          </div>
          <Filter id="user-role" label="Role" value={role} onChange={setRole} all="All roles" options={(["FACULTY", "HOD", "PRINCIPAL", "DIRECTOR", "ADMIN"] as Role[]).map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
          <Filter id="user-status" label="Status" value={status} onChange={setStatus} all="Any status" options={[{ value: "ACTIVE", label: "Active" }, { value: "DISABLED", label: "Disabled" }]} />
          <Filter id="user-department" label="Department" value={department} onChange={setDepartment} all="All departments" options={reference.departments.map((d) => ({ value: String(d.id), label: d.code }))} />
          <Filter id="user-cadre" label="Designation" value={cadre} onChange={setCadre} all="All designations" options={reference.cadres.map((c) => ({ value: c.name, label: c.name }))} />
          <Filter
            id="user-signin"
            label="Sign-in"
            value={signIn}
            onChange={setSignIn}
            all="Any"
            options={[
              { value: "active", label: "Has signed in" },
              { value: "never", label: "Never signed in" },
              { value: "pending", label: "Has not chosen a password" },
            ]}
          />
          <Filter id="user-sort" label="Sort by" value={sort} onChange={(v) => setSort(v as Sort)} all={null} options={SORTS.map((o) => ({ value: o.value, label: o.label }))} />
        </div>
        <div className="flex flex-wrap items-center gap-3 text-sm text-muted" role="status">
          <span>
            {narrowed ? `Showing ${shown.length} of ${rows.length}` : `${rows.length}`} account{(narrowed ? shown.length : rows.length) === 1 ? "" : "s"}
            {rows.length >= 500 ? " (the first 500; narrow the search)" : ""}
          </span>
          {(narrowed || query || role || sort) && <Button variant="ghost" size="sm" onClick={clearAll}>Clear all filters</Button>}
        </div>
      </div>

      {shown.length === 0 ? (
        <EmptyState>{query || role || narrowed ? "No accounts match your search." : "There are no accounts yet."}</EmptyState>
      ) : (
        <>
        <ul className="rise grid gap-3 sm:hidden" style={{ "--i": 2 } as React.CSSProperties} aria-label="Accounts">
          {shown.map((u) => {
            const self = u.id === me?.id;
            const closed = isWithdrawnRole(u.role);
            const department = u.role === "FACULTY" ? <>{u.department}<span className="block text-xs text-muted">{u.cadre}</span></> : u.role === "HOD" ? u.hodDepartments ?? "—" : "—";
            return (
              <li key={u.id} className="rounded-xl border border-line bg-surface p-4 shadow-[var(--shadow-paper)]">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="break-words font-display text-lg font-medium leading-snug text-navy">{u.name ?? u.email}</p>
                    {u.name && <p className="break-all text-xs text-muted">{u.email}</p>}
                    {u.employeeId && <p className="mt-0.5 text-xs text-muted">ID {u.employeeId}</p>}
                  </div>
                  <span className="shrink-0">
                    <Badge tone={u.status === "ACTIVE" ? "ok" : "bad"}>{u.status === "ACTIVE" ? "Active" : "Disabled"}</Badge>
                  </span>
                </div>
                {self && <span className="mt-2 inline-flex"><Badge tone="info">You</Badge></span>}
                {u.mustChangePassword && <p className="mt-2 text-xs font-medium text-warn">Has not yet chosen a password</p>}
                <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-line pt-3 text-sm">
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Role</dt><dd className="mt-0.5">{roleLabel(u.role)}</dd></div>
                  <div><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Last sign-in</dt><dd className="mt-0.5">{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : "Never"}</dd></div>
                  <div className="col-span-2"><dt className="text-[10px] font-bold uppercase tracking-[0.12em] text-muted">Department</dt><dd className="mt-0.5">{department}</dd></div>
                </dl>
                {closed ? <p className="mt-4 border-t border-line pt-3 text-xs text-muted">{WITHDRAWN_NOTE}</p> : (
                <div className="mt-4 grid grid-cols-2 gap-2 border-t border-line pt-3">
                  <Button variant="secondary" size="sm" className="w-full" onClick={() => setForm({ userId: u.id })}>Edit<span className="sr-only"> {u.email}</span></Button>
                  <Button variant="secondary" size="sm" className="w-full" disabled={self || working} title={self ? "Use Account > Change password for your own account" : undefined} onClick={() => setPending({ kind: "reset", user: u })}>Reset password<span className="sr-only"> for {u.email}</span></Button>
                  {u.status === "ACTIVE" ? (
                    <Button variant="secondary" size="sm" className="col-span-2 w-full" disabled={self || working} title={self ? "You cannot disable your own account" : undefined} onClick={() => setPending({ kind: "disable", user: u })}>Disable account<span className="sr-only"> {u.email}</span></Button>
                  ) : (
                    <Button variant="secondary" size="sm" className="col-span-2 w-full" disabled={working} onClick={() => void enable(u)}>Enable account<span className="sr-only"> {u.email}</span></Button>
                  )}
                </div>
                )}
              </li>
            );
          })}
        </ul>
        <div className="rise hidden overflow-x-auto rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:block" style={{ "--i": 2 } as React.CSSProperties}>
          <table className="w-full min-w-[56rem] border-collapse text-left text-sm">
            <caption className="sr-only">Accounts</caption>
            <thead>
              <tr className="bg-brand-soft text-navy">
                {["Person", "Role", "Department", "Status", "Last sign-in", ""].map((h, i) => (
                  <th key={i} scope="col" className="px-4 py-2.5 text-[11px] font-bold uppercase tracking-[0.1em]">
                    {h || <span className="sr-only">Actions</span>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {shown.map((u) => {
                const self = u.id === me?.id;
                const closed = isWithdrawnRole(u.role);
                return (
                  <tr key={u.id} className="border-t border-line align-top transition-colors hover:bg-warm/40">
                    <td className="px-4 py-2.5">
                      <span className="font-display text-base font-medium">{u.name ?? u.email}</span>
                      {u.name && <span className="block text-xs text-muted">{u.email}</span>}
                      {u.employeeId && <span className="block text-xs text-muted">ID {u.employeeId}</span>}
                      {self && <span className="mt-1 inline-block"><Badge tone="info">You</Badge></span>}
                    </td>
                    <td className="px-4 py-2.5">{roleLabel(u.role)}</td>
                    <td className="px-4 py-2.5">
                      {u.role === "FACULTY" ? (
                        <>
                          {u.department}
                          <span className="block text-xs text-muted">{u.cadre}</span>
                        </>
                      ) : u.role === "HOD" ? (
                        u.hodDepartments ?? "—"
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="px-4 py-2.5">
                      <Badge tone={u.status === "ACTIVE" ? "ok" : "bad"}>{u.status === "ACTIVE" ? "Active" : "Disabled"}</Badge>
                      {u.mustChangePassword && <span className="mt-1 block text-xs text-warn">Has not yet chosen a password</span>}
                    </td>
                    <td className="px-4 py-2.5 text-muted">{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : "Never"}</td>
                    <td className="px-4 py-2.5">
                      {closed ? <p className="max-w-64 text-right text-xs text-muted">{WITHDRAWN_NOTE}</p> : (
                      <div className="flex flex-wrap justify-end gap-1.5">
                        <Button variant="secondary" size="sm" onClick={() => setForm({ userId: u.id })}>
                          Edit<span className="sr-only"> {u.email}</span>
                        </Button>
                        <Button variant="secondary" size="sm" disabled={self || working} title={self ? "Use Account > Change password for your own account" : undefined} onClick={() => setPending({ kind: "reset", user: u })}>
                          Reset password<span className="sr-only"> for {u.email}</span>
                        </Button>
                        {u.status === "ACTIVE" ? (
                          <Button variant="secondary" size="sm" disabled={self || working} title={self ? "You cannot disable your own account" : undefined} onClick={() => setPending({ kind: "disable", user: u })}>
                            Disable<span className="sr-only"> {u.email}</span>
                          </Button>
                        ) : (
                          <Button variant="secondary" size="sm" disabled={working} onClick={() => void enable(u)}>
                            Enable<span className="sr-only"> {u.email}</span>
                          </Button>
                        )}
                      </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        </>
      )}

      <UserFormDialog
        open={form !== null}
        userId={form?.userId}
        reference={reference}
        onClose={() => setForm(null)}
        onCreated={(value) => {
          setIssued({ value, reason: "created" });
          void load();
        }}
        onSaved={() => {
          setNotice("Details saved.");
          void load();
        }}
      />
      <ImportAccountsDialog open={importing} reference={reference} onClose={() => setImporting(false)} onImported={() => void load()} />
      <OneTimePasswordDialog issued={issued?.value ?? null} reason={issued?.reason ?? "created"} onClose={() => setIssued(null)} />
      <ConfirmDialog
        open={pending !== null}
        destructive={pending?.kind === "disable"}
        title={pending?.kind === "disable" ? "Disable this account?" : "Reset this password?"}
        confirmLabel={pending?.kind === "disable" ? "Disable account" : "Reset password"}
        message={
          pending?.kind === "disable" ? (
            <p>
              <span className="font-semibold">{pending.user.email}</span> will be signed out at once and cannot sign in until you enable the account again. Their appraisals and
              history are kept.
            </p>
          ) : (
            <p>
              <span className="font-semibold">{pending?.user.email}</span> will be signed out everywhere and their current password stops working. Their password becomes
              the standard one, which they must replace at their next sign-in.
            </p>
          )
        }
        onConfirm={() => void confirm()}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}

function Filter({ id, label, value, onChange, all, options }: {
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  /** The wording of the "no filter" choice; null where every choice is a real one (a sort order). */
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
