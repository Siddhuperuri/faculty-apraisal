"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { PasswordInput } from "@/components/ui/PasswordInput";
import { Alert, Button, Card } from "@/components/ui/primitives";
import { ApiError, post } from "@/lib/api";

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetForm />
    </Suspense>
  );
}

/** The page the e-mailed link opens: the person chooses their own new password (a password is never e-mailed). */
function ResetForm() {
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setProblem(null);
    if (password !== again) {
      setProblem("The two passwords are not the same.");
      return;
    }
    setBusy(true);
    try {
      await post("/api/auth/reset-password", { token, newPassword: password });
      setDone(true);
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors?.newPassword) setProblem(err.fieldErrors.newPassword);
      else setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main id="main" className="mx-auto flex min-h-screen max-w-md items-center px-4 py-10">
      <Card className="w-full space-y-5 p-6 sm:p-8">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Faculty Self Appraisal</p>
          <h1 className="mt-1 font-display text-3xl font-medium text-navy">Choose a new password</h1>
        </div>

        {token === "" ? (
          <Alert tone="error" title="This link is not complete">
            Open the link from your e-mail again, or <Link href="/forgot-password" className="underline">ask for a new one</Link>.
          </Alert>
        ) : done ? (
          <>
            <Alert tone="info" title="Password changed">Your password has been changed. You can sign in with it now.</Alert>
            <Link href="/login" className="font-semibold text-brand underline">Go to sign in</Link>
          </>
        ) : (
          <form onSubmit={submit} className="space-y-4" noValidate>
            {error && (
              <Alert tone="error" title="Could not change the password">
                {error} <Link href="/forgot-password" className="underline">Ask for a new link</Link>.
              </Alert>
            )}
            <div>
              <label htmlFor="new-password" className="mb-1 block text-sm font-semibold">New password</label>
              <PasswordInput id="new-password" autoComplete="new-password" required value={password} onChange={(e) => setPassword(e.target.value)}
                className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-[15px]" />
            </div>
            <div>
              <label htmlFor="again" className="mb-1 block text-sm font-semibold">Type it again</label>
              <PasswordInput id="again" autoComplete="new-password" required value={again} onChange={(e) => setAgain(e.target.value)}
                className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-[15px]" />
            </div>
            {problem && <p role="alert" className="text-sm font-medium text-bad">{problem}</p>}
            <Button type="submit" loading={busy} disabled={password === "" || again === ""} className="w-full">Change my password</Button>
          </form>
        )}
      </Card>
    </main>
  );
}
