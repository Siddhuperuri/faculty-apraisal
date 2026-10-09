"use client";

import { useEffect, useId, useState } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { Alert, Button, Card } from "@/components/ui/primitives";
import { ApiError, get, post, put } from "@/lib/api";
import { ROLE_LABEL } from "@/lib/labels";
import { passwordChecks } from "@/lib/password";

const CONTROL =
  "block w-full rounded-sm border bg-surface px-3 py-2 text-[15px] text-ink shadow-[inset_0_1px_2px_rgb(20_30_54/0.06)] focus:border-brand focus:shadow-[0_0_0_3px_rgb(0_112_192/0.15)]";

function PasswordField({
  label,
  value,
  onChange,
  error,
  autoComplete,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  error?: string;
  autoComplete: string;
  autoFocus?: boolean;
}) {
  const id = `pw-${useId()}`;
  const [shown, setShown] = useState(false);
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-[13px] font-semibold tracking-wide">
        {label}
      </label>
      <div className="flex gap-2">
        <input
          id={id}
          type={shown ? "text" : "password"}
          value={value}
          autoComplete={autoComplete}
          autoFocus={autoFocus}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-err` : undefined}
          onChange={(e) => onChange(e.target.value)}
          className={`${CONTROL} ${error ? "border-bad" : "border-line-strong"}`}
        />
        <Button type="button" variant="secondary" size="sm" className="h-auto shrink-0" aria-pressed={shown} onClick={() => setShown((s) => !s)}>
          {shown ? "Hide" : "Show"}
        </Button>
      </div>
      {error && (
        <p id={`${id}-err`} className="mt-1 text-sm font-medium text-bad">
          {error}
        </p>
      )}
    </div>
  );
}

/**
 * The details a person may keep up to date themselves: the contact number. Everything else about them (name,
 * employee ID, department, designation, e-mail) is set by the administrator, because it decides how their appraisal is
 * scored and who reviews it. The administrator's own account has nothing to edit, so this card is not shown to them.
 */
function DetailsCard({ email }: { email: string }) {
  const [saved, setSaved] = useState<string | null | undefined>(undefined);   // undefined while loading
  const [contact, setContact] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [ok, setOk] = useState(false);
  const [busy, setBusy] = useState(false);
  const id = `contact-${useId()}`;

  useEffect(() => {
    let live = true;
    get<{ editable: boolean; contactNo: string | null }>("/api/auth/account")
      .then((d) => {
        if (!live) return;
        setSaved(d.contactNo);
        setContact(d.contactNo ?? "");
      })
      .catch(() => live && setError("Your details could not be loaded."));
    return () => {
      live = false;
    };
  }, []);

  if (saved === undefined && !error) return null;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setOk(false);
    setBusy(true);
    try {
      const d = await put<{ contactNo: string | null }>("/api/auth/account", { contactNo: contact });
      setSaved(d.contactNo);
      setContact(d.contactNo ?? "");
      setOk(true);
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors?.contactNo ?? err.message) : "Could not save. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  const unchanged = contact.trim() === (saved ?? "");
  return (
    <Card className="rise p-5">
      <form onSubmit={submit} noValidate className="space-y-4" aria-label="Your details">
        <div>
          <h2 className="font-display text-xl font-medium text-navy">Your details</h2>
          <p className="mt-0.5 text-xs text-muted">Your name, employee ID, department, designation and e-mail ({email}) are set by the administrator.</p>
        </div>
        <div>
          <label htmlFor={id} className="mb-1 block text-[13px] font-semibold tracking-wide">Contact number</label>
          <input
            id={id}
            type="tel"
            inputMode="tel"
            autoComplete="tel"
            maxLength={20}
            value={contact}
            aria-invalid={error ? true : undefined}
            onChange={(e) => {
              setContact(e.target.value);
              setOk(false);
            }}
            className={`${CONTROL} ${error ? "border-bad" : "border-line-strong"}`}
          />
          {error && <p className="mt-1 text-sm font-medium text-bad">{error}</p>}
          {ok && <p role="status" className="mt-1 text-sm font-medium text-ok">Saved.</p>}
        </div>
        <div className="flex justify-end border-t border-line pt-4">
          <Button type="submit" loading={busy} disabled={unchanged}>Save details</Button>
        </div>
      </form>
    </Card>
  );
}

/** Your own account: contact number, and the password (which the old password an administrator gives forces first). */
export default function AccountPage() {
  const { user, refresh, logout } = useAuth();
  const router = useRouter();
  const forced = !!user?.mustChangePassword;
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  if (!user) return null;

  const checks = passwordChecks(next, user.email);
  const mismatch = again.length > 0 && again !== next;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage(null);
    const local: Record<string, string> = {};
    if (!current) local.currentPassword = forced ? "Enter your old password." : "Enter your current password.";
    const failed = checks.find((c) => !c.ok);
    if (failed) local.newPassword = `Password rule not met: ${failed.rule.toLowerCase()}.`;
    if (again !== next) local.again = "The two new passwords do not match.";
    setErrors(local);
    if (Object.keys(local).length > 0) return;

    setBusy(true);
    try {
      await post("/api/auth/change-password", { currentPassword: current, newPassword: next });
      await refresh();
      setCurrent("");
      setNext("");
      setAgain("");
      setDone(true);
      if (forced) router.replace("/");
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors) {
        setErrors(err.fieldErrors);
        setMessage(null);
      } else {
        setMessage(err instanceof ApiError ? err.message : "Could not change the password. Please try again.");
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div className="rise">
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Your account</p>
        <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">{forced ? "Choose a new password" : "Account"}</h1>
        <p className="mt-1 text-sm text-muted">
          Signed in as <span className="font-semibold text-ink">{user.email}</span> · {ROLE_LABEL[user.role]}
        </p>
      </div>

      {forced && (
        <Alert tone="warn" title="Your password must be changed before you continue">
          An administrator gave you an old password. Enter it below as your old password, then choose one only you know.
        </Alert>
      )}
      {done && !forced && (
        <Alert tone="ok" title="Password changed">
          Your other sessions, if any, were signed out. This one stays signed in.
        </Alert>
      )}
      {message && <Alert tone="error" title="Could not change the password">{message}</Alert>}

      {!forced && user.role !== "ADMIN" && <DetailsCard email={user.email} />}

      <Card className="rise p-5" >
        <h2 className="mb-4 font-display text-xl font-medium text-navy">Change password</h2>
        <form onSubmit={submit} noValidate className="space-y-5" aria-label="Change password">
          <PasswordField label={forced ? "Old password" : "Current password"} value={current} onChange={setCurrent} error={errors.currentPassword} autoComplete="current-password" autoFocus />
          <PasswordField label="New password" value={next} onChange={setNext} error={errors.newPassword} autoComplete="new-password" />
          <ul className="password-checklist grid gap-2 text-xs sm:grid-cols-2" aria-label="Password rules">
            {checks.map((c) => (
              <li key={c.rule} className={`flex items-start gap-2 rounded-lg border px-3 py-2.5 leading-snug ${c.ok ? "border-ok/25 bg-ok-soft/60 text-ok" : "border-line bg-canvas/70 text-muted"}`}>
                <span aria-hidden className="font-bold">{c.ok ? "✓" : "○"}</span> {c.rule}
                <span className="sr-only">{c.ok ? " (met)" : " (not met yet)"}</span>
              </li>
            ))}
          </ul>
          <PasswordField label="Type the new password again" value={again} onChange={setAgain} error={errors.again ?? (mismatch ? "The two new passwords do not match." : undefined)} autoComplete="new-password" />
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line pt-4">
            {forced ? (
              <Button type="button" variant="ghost" onClick={() => void logout()}>
                Sign out instead
              </Button>
            ) : (
              <span className="text-xs text-muted">Changing it signs out your other sessions.</span>
            )}
            <Button type="submit" loading={busy}>
              Change password
            </Button>
          </div>
        </form>
      </Card>
    </div>
  );
}
