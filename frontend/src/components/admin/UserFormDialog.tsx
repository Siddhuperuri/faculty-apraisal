"use client";

import { useEffect, useState } from "react";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import { ChoiceInput, FieldInput } from "@/components/ui/FieldInput";
import { ApiError, get, post, put } from "@/lib/api";
import { ROLE_LABEL, isWithdrawnRole } from "@/lib/labels";
import type { AdminReference, AdminUserDetail, FieldMeta, IssuedPassword, Role } from "@/lib/types";

const ROLES: Role[] = ["FACULTY", "HOD", "PRINCIPAL", "DIRECTOR", "ADMIN"];

function meta(name: string, label: string, type: FieldMeta["type"], required: boolean, extra: Partial<FieldMeta> = {}): FieldMeta {
  return { name, label, type, required, maxLength: null, min: null, max: null, scale: null, allowed: null, ...extra };
}

/** The faculty record, in the order of Part A of the form. Limits match the server's. */
const IDENTITY: FieldMeta[] = [
  meta("name", "Name", "TEXT", true, { maxLength: 120 }),
  meta("employeeId", "Employee ID", "TEXT", true, { maxLength: 32 }),
  meta("contactNo", "Contact number", "TEXT", false, { maxLength: 20 }),
];
const DETAILS: FieldMeta[] = [
  meta("qualification", "Qualification", "TEXT", false, { maxLength: 160 }),
  meta("specialization", "Specialization", "TEXT", false, { maxLength: 160 }),
  meta("phdStatus", "Ph.D. status", "ENUM", false, { allowed: ["AWARDED", "PURSUING", "NOT_APPLICABLE"] }),
  meta("joiningDateInstitution", "Date of joining (institution)", "DATE", false),
  meta("joiningDateDesignation", "Date of joining (present designation)", "DATE", false),
  meta("teachingExperienceYears", "Teaching experience (years)", "DECIMAL", false, { min: 0, max: 80, scale: 1 }),
  meta("industryExperienceYears", "Industry experience (years)", "DECIMAL", false, { min: 0, max: 80, scale: 1 }),
  meta("researchExperienceYears", "Research experience (years)", "DECIMAL", false, { min: 0, max: 80, scale: 1 }),
  meta("orcid", "ORCID", "TEXT", false, { maxLength: 40 }),
  meta("scopusId", "Scopus ID", "TEXT", false, { maxLength: 40 }),
  meta("googleScholarId", "Google Scholar ID", "TEXT", false, { maxLength: 60 }),
  meta("vidwanId", "Vidwan ID", "TEXT", false, { maxLength: 40 }),
];
const PROFILE_FIELDS = [...IDENTITY, ...DETAILS];
/** What an account without a faculty record keeps about the person; the same three fields, none of them required. */
const OTHER_IDENTITY: FieldMeta[] = IDENTITY.map((f) => ({ ...f, required: false }));

const EMAIL = meta("email", "College e-mail address", "TEXT", true, { maxLength: 190 });

type Values = Record<string, string>;

function toValues(d: AdminUserDetail): Values {
  const v: Values = {};
  for (const f of PROFILE_FIELDS) {
    const raw = d.profile[f.name];
    v[f.name] = raw == null ? "" : String(raw);
  }
  v.departmentId = d.departmentId == null ? "" : String(d.departmentId);
  v.cadreId = d.cadreId == null ? "" : String(d.cadreId);
  return v;
}

/**
 * Create an account, or edit a person's details. A role never changes after creation (make a new account instead), and
 * neither does the e-mail address, which is the sign-in name. A new account starts with the standard password.
 */
export function UserFormDialog({
  open,
  userId,
  reference,
  onClose,
  onCreated,
  onSaved,
}: {
  open: boolean;
  /** Present to edit that account; absent to create one. */
  userId?: number;
  reference: AdminReference;
  onClose: () => void;
  onCreated: (issued: IssuedPassword) => void;
  onSaved: () => void;
}) {
  return (
    <Dialog open={open} onClose={onClose} title={userId ? "Edit account" : "Add an account"} wide>
      <Form userId={userId} reference={reference} onClose={onClose} onCreated={onCreated} onSaved={onSaved} />
    </Dialog>
  );
}

function Form({
  userId,
  reference,
  onClose,
  onCreated,
  onSaved,
}: {
  userId?: number;
  reference: AdminReference;
  onClose: () => void;
  onCreated: (issued: IssuedPassword) => void;
  onSaved: () => void;
}) {
  const editing = userId !== undefined;
  const [role, setRole] = useState<Role>("FACULTY");
  const [email, setEmail] = useState("");
  const [values, setValues] = useState<Values>({});
  const [hodIds, setHodIds] = useState<number[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [loading, setLoading] = useState(editing);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (userId === undefined) return;
    let alive = true;
    get<AdminUserDetail>(`/api/admin/users/${userId}`)
      .then((d) => {
        if (!alive) return;
        // Accounts of a withdrawn role have no Edit button; the server refuses to change them in any case.
        if (!isWithdrawnRole(d.row.role)) setRole(d.row.role);
        setEmail(d.row.email);
        setValues(toValues(d));
        setHodIds(d.hodDepartmentIds);
        setLoading(false);
      })
      .catch((e) => {
        if (!alive) return;
        setMessage((e as Error).message);
        setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [userId]);

  const set = (name: string) => (v: string) => setValues((s) => ({ ...s, [name]: v }));

  // A department or cadre the person already has stays selectable even if it has since been closed.
  const currentDept = Number(values.departmentId || 0);
  const currentCadre = Number(values.cadreId || 0);
  const deptOptions = reference.departments
    .filter((d) => d.active || d.id === currentDept)
    .map((d) => ({ value: String(d.id), label: `${d.code} · ${d.name}${d.active ? "" : " (closed)"}` }));
  const cadreOptions = reference.cadres
    .filter((c) => c.active || c.id === currentCadre)
    .map((c) => ({ value: String(c.id), label: c.name }));

  const toggleIn = (setter: React.Dispatch<React.SetStateAction<number[]>>) => (id: number) =>
    setter((ids) => (ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id]));

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage(null);
    const body: Record<string, unknown> = {};
    if (!editing) {
      body.role = role;
      body.email = email.trim();
    }
    if (role === "FACULTY") {
      for (const f of PROFILE_FIELDS) body[f.name] = (values[f.name] ?? "").trim();
      body.departmentId = values.departmentId ? Number(values.departmentId) : null;
      body.cadreId = values.cadreId ? Number(values.cadreId) : null;
    }
    if (role !== "FACULTY") {
      for (const f of OTHER_IDENTITY) body[f.name] = (values[f.name] ?? "").trim();
    }
    if (role === "HOD") body.hodDepartmentIds = hodIds;

    setBusy(true);
    try {
      if (editing) {
        await put<AdminUserDetail>(`/api/admin/users/${userId}`, body);
        onSaved();
        onClose();
      } else {
        const issued = await post<IssuedPassword>("/api/admin/users", body);
        onCreated(issued);
        onClose();
      }
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) {
        setErrors(err.fieldErrors);
        setMessage("Some details need attention. They are marked below.");
      } else {
        setMessage(err instanceof ApiError ? err.message : "Could not save. Please try again.");
      }
      setBusy(false);
    }
  };

  if (loading) {
    return (
      <div className="space-y-3" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-5">
      {message && <Alert tone="error" title="Not saved">{message}</Alert>}

      <div className="grid gap-4 sm:grid-cols-2">
        {editing ? (
          <div>
            <p className="mb-1 text-[13px] font-semibold tracking-wide">College e-mail address</p>
            <p className="rounded-sm border border-line bg-canvas px-3 py-2 text-[15px]">{email}</p>
            <p className="mt-1 text-xs text-muted">The e-mail address is the sign-in name and cannot be changed.</p>
          </div>
        ) : (
          <FieldInput meta={EMAIL} value={email} onChange={setEmail} error={errors.email} autoFocus />
        )}
        {editing ? (
          <div>
            <p className="mb-1 text-[13px] font-semibold tracking-wide">Role</p>
            <p className="rounded-sm border border-line bg-canvas px-3 py-2 text-[15px]">{ROLE_LABEL[role]}</p>
            <p className="mt-1 text-xs text-muted">A role cannot be changed. Add a new account if someone takes on another role.</p>
          </div>
        ) : (
          <ChoiceInput
            label="Role"
            required
            value={role}
            onChange={(v) => setRole(v as Role)}
            options={ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))}
            error={errors.role}
            placeholder="Choose a role"
          />
        )}
      </div>

      {role === "FACULTY" && (
        <>
          <fieldset className="space-y-4">
            <legend className="mb-2 border-b border-navy pb-1 font-display text-base font-semibold text-navy">Faculty record</legend>
            <div className="grid gap-4 sm:grid-cols-2">
              {IDENTITY.map((f) => (
                <FieldInput key={f.name} meta={f} value={values[f.name] ?? ""} onChange={set(f.name)} error={errors[f.name]} />
              ))}
              <ChoiceInput label="Department" required value={values.departmentId ?? ""} onChange={set("departmentId")} options={deptOptions} error={errors.departmentId} />
              <ChoiceInput label="Designation (cadre)" required value={values.cadreId ?? ""} onChange={set("cadreId")} options={cadreOptions} error={errors.cadreId} />
            </div>
          </fieldset>
          <fieldset className="space-y-4">
            <legend className="mb-2 border-b border-line-strong pb-1 font-display text-base font-semibold text-navy">
              More details <span className="text-xs font-normal text-muted">(they pre-fill Part A of each new appraisal)</span>
            </legend>
            <div className="grid gap-4 sm:grid-cols-2">
              {DETAILS.map((f) => (
                <FieldInput key={f.name} meta={f} value={values[f.name] ?? ""} onChange={set(f.name)} error={errors[f.name]} />
              ))}
            </div>
          </fieldset>
        </>
      )}

      {role === "HOD" && (
        <DepartmentPicker legend="Departments headed" departments={reference.departments} selected={hodIds} onToggle={toggleIn(setHodIds)} error={errors.hodDepartmentIds}
          help="Faculty of these departments submit their appraisals to this Head of the Department, who approves and forwards them to the Principal or the Director Technical." />
      )}

      {(role === "PRINCIPAL" || role === "DIRECTOR" || role === "ADMIN") && (
        <p className="text-sm text-muted">
          {role === "PRINCIPAL"
            ? "The Principal gives the final decision on appraisals the Heads of Department have approved and forwarded. The Director Technical can decide the same appraisals."
            : role === "DIRECTOR"
              ? "The Director Technical gives the final decision on appraisals the Heads of Department have approved and forwarded, for the whole college, in the same way as the Principal."
              : "Administrators manage accounts, departments, academic years and the scoring policy. They cannot read or change appraisal content."}
        </p>
      )}

      {role !== "FACULTY" && (
        <fieldset className="space-y-4">
          <legend className="mb-2 border-b border-line-strong pb-1 font-display text-base font-semibold text-navy">
            About the person <span className="text-xs font-normal text-muted">(optional)</span>
          </legend>
          <div className="grid gap-4 sm:grid-cols-2">
            {OTHER_IDENTITY.map((f) => (
              <FieldInput key={f.name} meta={f} value={values[f.name] ?? ""} onChange={set(f.name)} error={errors[f.name]} />
            ))}
          </div>
        </fieldset>
      )}

      {!editing && (
        <p className="text-sm text-muted">
          The account starts with the standard password, and the person must replace it when they first sign in.
        </p>
      )}

      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" loading={busy}>
          {editing ? "Save changes" : "Create account"}
        </Button>
      </div>
    </form>
  );
}

/** Choose the departments a Head of the Department heads. A closed department stays listed if the person already has it. */
function DepartmentPicker({
  legend,
  departments,
  selected,
  onToggle,
  error,
  help,
}: {
  legend: string;
  departments: AdminReference["departments"];
  selected: number[];
  onToggle: (id: number) => void;
  error?: string;
  help?: string;
}) {
  return (
    <fieldset>
      <legend className="mb-2 border-b border-navy pb-1 font-display text-base font-semibold text-navy">
        {legend} <span className="text-bad" aria-hidden>*</span>
        <span className="sr-only"> (required)</span>
      </legend>
      {help && <p className="mb-2 text-sm text-muted">{help}</p>}
      <div className="grid gap-2 sm:grid-cols-2">
        {departments
          .filter((d) => d.active || selected.includes(d.id))
          .map((d) => (
            <label key={d.id} className="flex cursor-pointer items-start gap-2 rounded-sm border border-line px-3 py-2 text-sm hover:bg-brand-soft">
              <input type="checkbox" checked={selected.includes(d.id)} onChange={() => onToggle(d.id)} className="mt-1" />
              <span>
                <span className="font-semibold">{d.code}</span> · {d.name}
                {!d.active && <span className="text-muted"> (closed)</span>}
              </span>
            </label>
          ))}
      </div>
      {error && <p className="mt-1 text-sm font-medium text-bad">{error}</p>}
    </fieldset>
  );
}
