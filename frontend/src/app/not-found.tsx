import type { Metadata } from "next";
import Image from "next/image";
import Link from "next/link";

export const metadata: Metadata = { title: "Page not found | Faculty Self Appraisal" };

/** Shown for any address that does not exist, and whenever a page calls notFound(). */
export default function NotFound() {
  return (
    <main id="main" className="flex min-h-screen items-center justify-center bg-[radial-gradient(ellipse_at_50%_0%,var(--glow),transparent_65%)] px-5 py-12">
      <div className="w-full max-w-md rounded-2xl border border-line/90 bg-surface/95 p-8 text-center shadow-[0_24px_70px_-42px_rgb(18_35_63/0.42)] sm:p-10">
        <span className="mx-auto flex h-16 w-16 items-center justify-center">
          <Image src="/svec-logo.png" alt="Sri Vasavi Engineering College seal" width={56} height={56} className="h-full w-full object-contain" />
        </span>
        <p className="mt-6 font-display text-6xl font-medium tracking-tight text-navy">404</p>
        <h1 className="mt-1 font-display text-2xl font-medium text-navy">Page not found</h1>
        <p className="mt-3 text-sm leading-relaxed text-muted">
          This page does not exist, or the link is out of date. Your appraisal and anything you have saved are not affected.
        </p>
        <Link
          href="/"
          className="mt-7 inline-flex h-11 items-center justify-center rounded-sm bg-fill px-6 text-[15px] font-semibold text-white hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2"
        >
          Go to home page
        </Link>
      </div>
    </main>
  );
}
