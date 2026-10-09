"use client";

import { Suspense, useEffect, useState } from "react";
import Image from "next/image";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { PasswordInput } from "@/components/ui/PasswordInput";
import { Alert, Button } from "@/components/ui/primitives";
import { ApiError } from "@/lib/api";

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}

/** A 16-pixel stand-in for the campus photograph, shown for the instant before the real one has loaded. */
const BACKDROP_BLUR = "data:image/webp;base64,UklGRk4AAABXRUJQVlA4IEIAAADQAQCdASoQAAkAA4BaJQBOgB59lRkGQAD2RZl1O4fH3cvnKDvaxFMfiVjRzwl492rkpGcGD/7g1LJpiDI3Rc7AAAA=";

/** Lines from the college's own letterhead (the official form). */
const ACCREDITATION = ["Accredited by NAAC with ‘A’ Grade", "Approved by AICTE, New Delhi", "Permanently affiliated to JNTUK, Kakinada"];

function LoginForm() {
  const { user, login } = useAuth();
  const router = useRouter();
  const expired = useSearchParams().get("expired") === "1";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (user) router.replace("/");
  }, [user, router]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await login(email.trim(), password);
      router.replace("/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not sign in. Please try again.");
      setBusy(false);
    }
  };

  // Glass fields: translucent, with a visible edge (3:1 against the card) rather than relying on fill alone.
  const field = "block w-full rounded-sm border border-white/35 bg-white/[0.07] px-3 py-2.5 text-[15px] text-white placeholder:text-white/50";

  return (
    <div className="auth-dark relative isolate min-h-dvh overflow-hidden bg-[#0b1220] text-ink">
      {/* The campus, full screen, under a dark veil so the text and the form stay readable on any screen size. */}
      <Image
        src="/campus-login.webp"
        alt=""
        fill
        priority
        sizes="100vw"
        placeholder="blur"
        blurDataURL={BACKDROP_BLUR}
        className="-z-20 object-cover object-[50%_40%]"
      />
      <div
        aria-hidden
        className="absolute inset-0 -z-10 bg-[linear-gradient(to_bottom,rgb(7_12_24/0.74),rgb(7_12_24/0.62)_45%,rgb(7_12_24/0.78))] lg:bg-[linear-gradient(100deg,rgb(7_12_24/0.82),rgb(7_12_24/0.62)_55%,rgb(7_12_24/0.7))]"
      />

      <div className="mx-auto grid min-h-dvh w-full max-w-[1560px] gap-8 px-5 py-8 sm:px-10 sm:py-12 lg:grid-cols-[1.1fr_1fr] lg:gap-16 lg:px-16 lg:py-14">
        {/* The college */}
        <header className="flex flex-col justify-between gap-6 lg:gap-10">
          <div className="rise flex items-center gap-3 lg:gap-4" style={{ "--i": 0 } as React.CSSProperties}>
            <span className="flex h-14 w-14 shrink-0 items-center justify-center drop-shadow-[0_2px_10px_rgb(0_0_0/0.45)] lg:h-24 lg:w-24">
              <Image src="/svec-logo.png" alt="Sri Vasavi Engineering College seal" width={96} height={96} priority className="h-full w-full object-contain" />
            </span>
            <p className="font-display text-lg font-medium leading-tight text-white [text-shadow:0_1px_2px_rgb(0_0_0/0.55),0_3px_16px_rgb(0_0_0/0.65)] sm:text-xl lg:text-2xl">
              Sri Vasavi Engineering College
              <span className="block text-sm italic text-white/85 lg:text-base">(Autonomous)</span>
            </p>
          </div>
          <div className="rise space-y-3 lg:space-y-4" style={{ "--i": 1 } as React.CSSProperties}>
            <h1 className="font-display text-3xl font-medium leading-[1.1] tracking-tight text-white [text-shadow:0_2px_3px_rgb(0_0_0/0.5),0_4px_22px_rgb(0_0_0/0.7)] sm:text-4xl xl:text-5xl">
              <span className="sm:whitespace-nowrap">Faculty Self Appraisal</span>
              <br />
              &amp; Assessment
            </h1>
            <div className="letterhead-rule max-w-[12rem]" aria-hidden />
            <ul className="hidden space-y-1 text-sm text-white/90 [text-shadow:0_1px_2px_rgb(0_0_0/0.6),0_2px_10px_rgb(0_0_0/0.6)] lg:block">
              {ACCREDITATION.map((l) => (
                <li key={l}>{l}</li>
              ))}
            </ul>
          </div>
          <p className="rise hidden text-xs uppercase tracking-[0.18em] text-white/80 [text-shadow:0_1px_2px_rgb(0_0_0/0.6),0_2px_10px_rgb(0_0_0/0.6)] lg:block" style={{ "--i": 2 } as React.CSSProperties}>
            Pedatadepalli, Tadepalligudem – 534 101
          </p>
        </header>

        {/* The form, on glass */}
        <main id="main" className="flex items-start justify-center lg:items-center lg:justify-end">
          <div className="auth-glass rise w-full max-w-md rounded-2xl p-6 sm:p-9" style={{ "--i": 3 } as React.CSSProperties}>
            <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand [text-shadow:0_1px_8px_rgb(0_0_0/0.6)]">Welcome</p>
            <h2 className="mt-0.5 font-display text-[2.65rem] font-medium tracking-tight text-white [text-shadow:0_2px_3px_rgb(0_0_0/0.45),0_4px_18px_rgb(0_0_0/0.6)] sm:text-5xl">Sign in</h2>
            <p className="mt-2 text-sm leading-relaxed text-white/85 [text-shadow:0_1px_2px_rgb(0_0_0/0.5),0_1px_8px_rgb(0_0_0/0.5)]">Use your college e-mail address to open your appraisal.</p>

            {expired && !error && (
              <div className="mt-5">
                <Alert tone="warn" title="Your session ended">Please sign in again. Anything you had saved is safe.</Alert>
              </div>
            )}
            {error && (
              <div className="mt-5">
                <Alert tone="error" title="Could not sign in">{error}</Alert>
              </div>
            )}

            <form onSubmit={submit} className="mt-7 space-y-5" noValidate>
              <div>
                <label htmlFor="email" className="mb-1.5 block text-sm font-semibold text-white [text-shadow:0_1px_6px_rgb(0_0_0/0.55)]">E-mail address</label>
                <input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} className={field} />
              </div>
              <div>
                <label htmlFor="password" className="mb-1.5 block text-sm font-semibold text-white [text-shadow:0_1px_6px_rgb(0_0_0/0.55)]">Password</label>
                <PasswordInput id="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} className={field} />
              </div>
              <Button type="submit" loading={busy} disabled={!email || !password} className="h-11 w-full text-[15px]">
                Sign in
              </Button>
            </form>
            <p className="mt-8 border-t border-white/15 pt-5 text-xs leading-relaxed text-white/80 [text-shadow:0_1px_6px_rgb(0_0_0/0.55)]">
              Forgot your password? Ask an administrator to reset it for you.
            </p>
          </div>
        </main>
      </div>
    </div>
  );
}
