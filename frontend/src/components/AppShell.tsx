"use client";

import Image from "next/image";
import Link from "next/link";
import { useAuth } from "@/components/auth/AuthProvider";
import { ThemeToggle } from "@/components/ThemeToggle";
import { Button } from "@/components/ui/primitives";
import { ROLE_LABEL } from "@/lib/labels";

/** The college's letterhead: seal, name in tracked capitals, and the programme line. */
export function Brand({ inverse = false }: { inverse?: boolean }) {
  return (
    <span className="flex items-center gap-3.5">
      <span className="flex h-12 w-12 shrink-0 items-center justify-center drop-shadow-[0_2px_8px_rgb(0_0_0/0.35)] sm:h-[3.35rem] sm:w-[3.35rem]">
        <Image src="/svec-logo.png" alt="" width={44} height={44} priority className="h-full w-full rounded-lg object-contain" />
      </span>
      <span className="leading-tight">
        <span className={`block font-display text-[15px] font-semibold uppercase tracking-[0.09em] sm:text-[17px] ${inverse ? "text-white" : "text-navy"}`}>
          Sri Vasavi Engineering College
        </span>
        <span className={`mt-1 hidden text-[10px] font-medium uppercase tracking-[0.17em] sm:block ${inverse ? "text-white/70" : "text-muted"}`}>
          Autonomous · Faculty Self Appraisal &amp; Assessment
        </span>
      </span>
    </span>
  );
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, logout } = useAuth();
  return (
    <div className="flex min-h-screen flex-col">
      <header className="sticky top-0 z-30 border-b border-white/10 bg-[linear-gradient(112deg,var(--hdr-a)_0%,var(--hdr-b)_58%,var(--hdr-c)_100%)] text-white shadow-[0_8px_28px_-22px_rgb(18_35_63/0.8)]">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3.5 sm:px-6">
          <Link href="/" aria-label="Home" className="rounded-md">
            <Brand inverse />
          </Link>
          {user && (
            <div className="ml-auto flex items-center gap-1.5 text-sm sm:gap-4">
              <span className="hidden items-center gap-3 rounded-full border border-white/15 bg-white/[0.07] py-1.5 pl-1.5 pr-4 leading-tight lg:flex">
                <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/10 font-display text-sm font-semibold text-ochre" aria-hidden>
                  {user.email.slice(0, 1).toUpperCase()}
                </span>
                <span className="text-right">
                  <span className="block max-w-52 truncate text-[13px] font-medium">{user.email}</span>
                  <span className="mt-0.5 block font-display text-xs italic text-white/70">{ROLE_LABEL[user.role]}</span>
                </span>
              </span>
              <ThemeToggle className="text-white/85 hover:bg-white/10 hover:text-white focus-visible:outline-white" />
              {!user.mustChangePassword && (
                <Link href="/account" className="rounded-full px-3 py-2 text-[13px] font-semibold text-white/85 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-white">
                  Account
                </Link>
              )}
              <Button variant="secondary" size="sm" onClick={() => void logout()}>
                Sign out
              </Button>
            </div>
          )}
        </div>
        <div className="letterhead-rule" aria-hidden />
      </header>
      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 sm:py-10 lg:py-12">
        {children}
      </main>
      <footer className="px-4 pb-7 pt-3 text-center text-xs text-muted">
        <div className="mx-auto mb-4 h-px max-w-sm bg-gradient-to-r from-transparent via-line-strong to-transparent" aria-hidden />
        <p className="font-display text-sm italic text-navy/80">Sri Vasavi Engineering College (Autonomous)</p>
        <p>Pedatadepalli, Tadepalligudem – 534 101, W.G. Dist, (A.P.)</p>
      </footer>
    </div>
  );
}
