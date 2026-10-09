"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { PAGES, type PageUi } from "@/lib/formStructure";
import { useAppraisal } from "./AppraisalProvider";

function entries(page: PageUi, counts: Record<string, number>, singleton: (k: string) => boolean): string | null {
  const keys = Array.from(new Set(page.sections.map((s) => s.key)));
  const total = keys.reduce((n, k) => n + (counts[k] ?? 0), 0);
  if (total === 0) return null;
  return keys.every(singleton) ? "Filled in" : String(total);
}

/** The form's contents, set like a ledger index: serif numerals, a gold marker on the page you are on. */
export function SectionNav() {
  const { id, counts, metas } = useAppraisal();
  const pathname = usePathname();
  const base = `/appraisals/${id}`;
  const isSingleton = (k: string) => metas?.[k]?.singleton ?? false;

  const row = (active: boolean) =>
    `group grid grid-cols-[2.1rem_minmax(0,1fr)_auto] items-baseline gap-2 rounded-md px-3 py-2 text-sm transition-colors duration-150 ${
      active ? "bg-fill text-white shadow-[inset_3px_0_0_var(--color-ochre)]" : "text-ink hover:bg-brand-soft"
    }`;
  const num = (active: boolean) => `font-display text-lg tabular-nums ${active ? "text-ochre" : "text-navy/60 group-hover:text-navy"}`;
  const tag = (active: boolean) => `rounded-full px-1.5 text-[11px] font-semibold tabular-nums ${active ? "bg-white/15 text-white" : "bg-brand-soft text-navy"}`;

  const items = (
    <ol className="space-y-0.5">
      {PAGES.map((p) => {
        const href = `${base}/${p.slug}`;
        const active = pathname === href;
        const info = entries(p, counts, isSingleton);
        return (
          <li key={p.slug}>
            <Link href={href} aria-current={active ? "page" : undefined} className={row(active)}>
              <span className={num(active)}>{p.number}</span>
              <span className="leading-snug">{p.title}</span>
              {info && <span className={tag(active)} aria-label={info === "Filled in" ? "filled in" : `${info} entries`}>{info === "Filled in" ? "✓" : info}</span>}
            </Link>
          </li>
        );
      })}
      <li className="mt-1 border-t border-line pt-1">
        <Link href={base} aria-current={pathname === base ? "page" : undefined} className={row(pathname === base)}>
          <span className={num(pathname === base)}>12</span>
          <span className="leading-snug">Score &amp; Review</span>
          <span />
        </Link>
      </li>
    </ol>
  );

  const current = PAGES.find((p) => pathname === `${base}/${p.slug}`);
  return (
    <>
      {/* Phones and small tablets: a collapsible index. */}
      <details className="appraisal-index rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] xl:hidden">
        <summary className="cursor-pointer px-4 py-3.5 text-sm font-semibold">
          Sections <span className="font-normal text-muted">· {current ? `${current.number} ${current.title}` : "12 Score & Review"}</span>
        </summary>
        <nav aria-label="Appraisal sections" className="border-t border-line p-2">
          {items}
        </nav>
      </details>
      {/* Desktop: a persistent index. */}
      <nav aria-label="Appraisal sections" className="appraisal-index sticky top-24 hidden rounded-xl border border-line bg-surface p-2.5 shadow-[var(--shadow-paper)] xl:block">
        <p className="px-3 pb-2 pt-2 text-[10px] font-bold uppercase tracking-[0.22em] text-muted">Contents</p>
        {items}
      </nav>
    </>
  );
}
