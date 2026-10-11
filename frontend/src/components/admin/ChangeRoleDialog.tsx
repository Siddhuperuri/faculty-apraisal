"use client";

import { useState } from "react";
import { Dialog } from "@/components/ui/Dialog";
import { ChoiceInput } from "@/components/ui/FieldInput";
import { Alert, Button } from "@/components/ui/primitives";
import { ApiError, put } from "@/lib/api";
import { ROLE_LABEL } from "@/lib/labels";
import type { AdminReference, AdminUserRow, Role } from "@/lib/types";

const ROLES: Role[] = ["FACULTY", "HOD", "PRINCIPAL", "DIRECTOR", "ADMIN"];

/**
 * Gives an existing user a different role (for example the Administrator role). The person's sessions end, so the new role
 * takes effect at their next sign-in; what they entered is untouched, and the change is written to the audit trail.
 * A Head of the Department also needs the departments they head.
 */
export function ChangeRoleDialog({
  user,
  reference,
  onClose,
  onChanged,
}: {
  user: AdminUserRow | null;
  reference: AdminReference;
  onClose: () => void;
  onChanged: (message: string) => void;
}) {
  return (
    <Dialog open={user !== null} onClose={onClose} title={user ? `Change role: ${user.name ?? user.email}` : ""}>
      {user && <Form user={user} reference={reference} onClose={onClose} onChanged={onChanged} />}
    </Dialog>
  );
}

function Form({ user, reference, onClose, onChanged }: { user: AdminUserRow; reference: AdminReference; onClose: () => void; onChanged: (m: string) => void }) {
  const [role, setRole] = useState<string>("");
  const [departments, setDepartments] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [problems, setProblems] = useState<Record<string, string>>({});

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setProblems({});
    try {
      await put(`/api/admin/users/${user.id}/role`, { role, ...(role === "HOD" ? { hodDepartmentIds: departments } : {}) });
      onChanged(`${user.name ?? user.email} is now ${ROLE_LABEL[role as Role]}. They must sign in again for it to take effect.`);
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) setProblems(err.fieldErrors);
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <p className="text-sm">
        <span className="font-semibold">{user.email}</span> is now <span className="font-semibold">{ROLE_LABEL[user.role as Role] ?? user.role}</span>.
        Their sessions will end so the new role applies at their next sign-in.
      </p>
      {error && Object.keys(problems).length === 0 && <Alert tone="error" title="Not changed">{error}</Alert>}
      <ChoiceInput
        label="New role"
        value={role}
        options={ROLES.filter((r) => r !== user.role).map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
        onChange={setRole}
        error={problems.role}
        required
      />
      {role === "HOD" && (
        <fieldset>
          <legend className="mb-1 text-[13px] font-semibold">Departments they head<span aria-hidden className="ml-0.5 text-bad">*</span></legend>
          <div className="grid gap-1 sm:grid-cols-2">
            {reference.departments.filter((d) => d.active).map((d) => (
              <label key={d.id} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={departments.includes(d.id)}
                  onChange={(e) => setDepartments((cur) => (e.target.checked ? [...cur, d.id] : cur.filter((x) => x !== d.id)))}
                />
                {d.code} <span className="text-muted">{d.name}</span>
              </label>
            ))}
          </div>
          {problems.hodDepartmentIds && <p className="mt-1 text-sm font-medium text-bad">{problems.hodDepartmentIds}</p>}
        </fieldset>
      )}
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" loading={busy} disabled={role === "" || (role === "HOD" && departments.length === 0)}>Change role</Button>
      </div>
    </form>
  );
}
