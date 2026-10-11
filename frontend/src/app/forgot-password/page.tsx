"use client";

import Link from "next/link";
import { useState } from "react";
import { Alert, Button, Card } from "@/components/ui/primitives";
import { ApiError, post } from "@/lib/api";

/**
 * Asks for a link to choose a new password. The answer is the same whether or not the address has an account, so the
 * page cannot be used to find out who has one.
 */
export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await post("/api/auth/forgot-password", { email: email.trim() });
      setSent(true);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <main id="main" className="mx-auto flex min-h-screen max-w-md items-center px-4 py-10">
      <Card className="w-full space-y-5 p-6 sm:p-8">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Faculty Self Appraisal</p>
          <h1 className="mt-1 font-display text-3xl font-medium text-navy">Forgot your password?</h1>
          <p className="mt-2 text-sm text-muted">
            Enter the college e-mail address of your account. If it is registered we will send a link to it that lets you choose a
            new password. The link works once and expires in 30 minutes.
          </p>
        </div>

        {sent ? (
          <Alert tone="info" title="Check your e-mail">
            If that address belongs to an account, a link to choose a new password has been sent to it. It may take a few minutes to
            arrive.
          </Alert>
        ) : (
          <form onSubmit={submit} className="space-y-4" noValidate>
            {error && <Alert tone="error" title="Could not send">{error}</Alert>}
            <div>
              <label htmlFor="email" className="mb-1 block text-sm font-semibold">E-mail address</label>
              <input
                id="email"
                type="email"
                autoComplete="username"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-[15px]"
              />
            </div>
            <Button type="submit" loading={busy} disabled={email.trim() === ""} className="w-full">Send the link</Button>
          </form>
        )}

        <p className="text-sm"><Link href="/login" className="font-semibold text-brand underline">Back to sign in</Link></p>
      </Card>
    </main>
  );
}
