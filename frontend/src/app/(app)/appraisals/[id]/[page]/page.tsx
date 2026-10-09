"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { CriterionMarks } from "@/components/appraisal/CriterionMarks";
import { IdentityBlock } from "@/components/appraisal/IdentityBlock";
import { FocusOnArrival, RequirementsSummary } from "@/components/appraisal/RequirementsSummary";
import { SectionView } from "@/components/appraisal/SectionView";
import { Alert, Card, linkButton } from "@/components/ui/primitives";
import { nextPrev, pageBySlug } from "@/lib/formStructure";


export default function SectionPage() {
  const { id, page } = useParams<{ id: string; page: string }>();
  const ui = pageBySlug(page);
  if (!ui) return <Alert tone="error" title="Page not found">There is no such section in this form.</Alert>;

  const { prev, next } = nextPrev(ui.slug);
  const base = `/appraisals/${id}`;

  return (
    <div className="space-y-6">
      <header className="flex items-end gap-5">
        <span aria-hidden className="numeral-outline select-none text-7xl sm:text-8xl">{ui.number}</span>
        <div className="pb-1">
          <p className="text-[11px] font-semibold uppercase tracking-[0.26em] text-brand">{ui.formRef}</p>
          <h2 className="font-display text-3xl font-medium tracking-tight text-navy">
            <span className="sr-only">{ui.number}. </span>
            {ui.title}
          </h2>
          <p className="mt-1 max-w-2xl text-sm text-muted">{ui.description}</p>
        </div>
      </header>

      <FocusOnArrival />
      <RequirementsSummary />

      {ui.criterion && <CriterionMarks criterion={ui.criterion} />}

      {ui.identity && (
        <Card className="p-4 sm:p-5">
          <IdentityBlock />
        </Card>
      )}

      {ui.sections.map((s) => (
        <Card key={`${s.key}-${s.scope?.value ?? ""}`} className="p-4 sm:p-5">
          <SectionView ui={s} showTitle={ui.sections.length > 1 || s.title !== "Your details"} />
        </Card>
      ))}

      <nav aria-label="Previous and next section" className="appraisal-paging flex items-center justify-between gap-3 border-t border-line pt-4 pb-6">
        {prev ? (
          <Link href={`${base}/${prev.slug}`} className={linkButton("secondary")}>
            ← Previous: {prev.title}
          </Link>
        ) : (
          <span />
        )}
        <Link href={next ? `${base}/${next.slug}` : base} className={linkButton("primary")}>
          {next ? `Next: ${next.title} →` : "Next: Score & Review →"}
        </Link>
      </nav>
    </div>
  );
}
