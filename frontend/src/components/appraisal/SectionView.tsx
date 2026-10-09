"use client";

import { useEffect } from "react";
import { Alert, RuledHeading, Skeleton } from "@/components/ui/primitives";
import type { SectionUi } from "@/lib/formStructure";
import { enumLabel } from "@/lib/labels";
import { useAppraisal } from "./AppraisalProvider";
import { MetricsTable } from "./MetricsTable";
import { RecordsSection } from "./RecordsSection";
import { SingleSection } from "./SingleSection";

/** Picks the right editor for a section from the server's description of it. */
export function SectionView({ ui, showTitle = true }: { ui: SectionUi; showTitle?: boolean }) {
  const { metas, metaError } = useAppraisal();
  const meta = metas?.[ui.key];

  let body: React.ReactNode;
  if (metaError) body = <Alert tone="error" title="Could not load the form definition">{metaError}</Alert>;
  else if (!meta) body = <Skeleton className="h-40 w-full" />;
  else if (ui.gate) body = <Gated ui={ui} />;
  else if (ui.fixedBy) body = <MetricsTable ui={ui} />;
  else if (meta.singleton) body = <SingleSection ui={ui} />;
  else body = <RecordsSection ui={ui} />;

  return (
    <section aria-labelledby={`sec-${ui.key}-${ui.scope?.value ?? "all"}`} className="space-y-3">
      {showTitle && (
        <RuledHeading id={`sec-${ui.key}-${ui.scope?.value ?? "all"}`}>{ui.title}</RuledHeading>
      )}
      {body}
    </section>
  );
}

/** A single-record section that only applies while another answer has a given value (e.g. Ph.D. status). */
function Gated({ ui }: { ui: SectionUi }) {
  const gate = ui.gate!;
  const { section, loadSection } = useAppraisal();
  useEffect(() => loadSection(gate.section), [loadSection, gate.section]);

  const st = section(gate.section);
  if (!st.data) return <Skeleton className="h-24 w-full" />;
  const current = st.data.records[0]?.[gate.field];
  if (current !== gate.equals) {
    return (
      <Alert tone="info" title={`Not applicable (Ph.D. status: ${enumLabel(String(current ?? "NOT_APPLICABLE"))})`}>
        {gate.message} Anything you entered earlier is kept.
      </Alert>
    );
  }
  return <SingleSection ui={ui} />;
}
