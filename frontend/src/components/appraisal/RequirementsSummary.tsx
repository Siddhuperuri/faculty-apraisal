"use client";

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { focusTarget } from "@/lib/focus";
import { buildRequirements } from "@/lib/requirements";
import { useAppraisal } from "./AppraisalProvider";

const GENERAL = "general-information";

/**
 * What still has to be completed before the appraisal can be submitted, section by section, each with links straight to
 * the field. The server's own list decides what is missing, so this shrinks as entries are saved. Shown to the faculty
 * member only, and only while the appraisal can still be edited and something is missing.
 */
export function RequirementsSummary() {
  const { id, appraisal, metas, section, loadSection } = useAppraisal();
  const blockers = useMemo(() => appraisal?.submitBlockers ?? [], [appraisal?.submitBlockers]);
  const needsGeneral = blockers.some((b) => b.startsWith("General Information"));

  useEffect(() => {
    if (needsGeneral) loadSection(GENERAL);
  }, [needsGeneral, loadSection]);

  const { data } = section(GENERAL);
  const items = useMemo(
    () => buildRequirements({ blockers, scores: appraisal?.scores ?? [], general: { meta: metas?.[GENERAL], record: data ? data.records[0] ?? null : undefined } }),
    [blockers, appraisal?.scores, metas, data],
  );

  if (!appraisal?.editable || items.length === 0) return null;
  const base = `/appraisals/${id}`;

  return (
    <section aria-labelledby="requirements-h" className="rounded-xl border border-warn/40 border-l-4 bg-warn-soft px-4 py-3.5 text-sm shadow-[var(--shadow-paper)] sm:px-5">
      <h2 id="requirements-h" className="font-semibold text-warn">
        {items.length === 1 ? "1 section needs attention before you can submit" : `${items.length} sections need attention before you can submit`}
      </h2>
      <ul className="mt-2 space-y-1.5">
        {items.map((r) => (
          <li key={r.key} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
            <span className="font-semibold text-ink">{r.heading}:</span>
            <span className="text-ink/90">{r.summary}</span>
            {r.links.length > 0 && (
              <span className="flex flex-wrap gap-x-3">
                {r.links.map((l) => (
                  <Link
                    key={l.label}
                    href={`${base}${r.page ? `/${r.page}` : ""}${l.focus ? `?focus=${encodeURIComponent(l.focus)}` : ""}`}
                    onClick={(e) => {
                      // Already on that page: go straight to the field instead of reloading it.
                      if (l.focus && focusTarget(l.focus)) e.preventDefault();
                    }}
                    className="font-medium text-brand underline underline-offset-2 hover:text-navy"
                  >
                    {l.label}
                  </Link>
                ))}
              </span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * On arrival from a summary link (`?focus=`), moves to that field once the page has drawn it. The section data loads
 * after the page appears, so it tries for a few seconds rather than once.
 */
export function FocusOnArrival() {
  useEffect(() => {
    const target = new URLSearchParams(window.location.search).get("focus");
    if (!target) return;
    let tries = 0;
    const timer = window.setInterval(() => {
      tries += 1;
      if (focusTarget(target) || tries >= 25) window.clearInterval(timer);
    }, 200);
    return () => window.clearInterval(timer);
  }, []);
  return null;
}
