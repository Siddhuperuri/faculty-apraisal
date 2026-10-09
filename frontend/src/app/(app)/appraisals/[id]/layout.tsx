"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { AppraisalProvider, useAppraisal } from "@/components/appraisal/AppraisalProvider";
import { SaveStatus } from "@/components/appraisal/SaveStatus";
import { SectionNav } from "@/components/appraisal/SectionNav";
import { useAuth } from "@/components/auth/AuthProvider";
import { Alert, PageRuler, Skeleton, StatusBadge } from "@/components/ui/primitives";
import { STATUS_INFO } from "@/lib/labels";

export default function AppraisalLayout({ children }: { children: React.ReactNode }) {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  if (!Number.isInteger(id) || id <= 0) return <Alert tone="error" title="Not found">That appraisal address is not valid.</Alert>;
  return (
    <AppraisalProvider key={id} id={id}>
      <Shell>{children}</Shell>
    </AppraisalProvider>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  const { appraisal, appraisalError, counts } = useAppraisal();
  const { user } = useAuth();
  const isFaculty = user?.role === "FACULTY";
  const back = isFaculty ? { href: "/faculty", label: "Dashboard" } : { href: "/review", label: "Review queue" };

  if (appraisalError) {
    return (
      <div className="space-y-3">
        <Link href={back.href} className="text-sm text-brand hover:underline">← {back.label}</Link>
        <Alert tone="error" title="Could not open this appraisal">{appraisalError}</Alert>
      </div>
    );
  }
  if (!appraisal) {
    return <div className="space-y-4" aria-busy="true"><Skeleton className="h-10 w-2/3" /><Skeleton className="h-64 w-full" /></div>;
  }

  return (
    <div className="appraisal-workspace space-y-6">
      <div className="rise">
        <Link href={back.href} className="inline-flex min-h-10 items-center gap-2 rounded-full px-3 text-sm font-semibold text-brand transition-colors hover:bg-brand-soft focus-visible:bg-brand-soft">← <span>{back.label}</span></Link>
        <div className="mt-2 flex flex-wrap items-end justify-between gap-4 border-b-2 border-navy/80 pb-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.26em] text-brand">Faculty Self Appraisal &amp; Assessment Report</p>
            <h1 className="mt-1 font-display text-[1.75rem] font-medium leading-tight tracking-tight text-navy sm:text-4xl">
              {appraisal.academicYear.replace("-", "–")} <span className="italic text-ink/80">· {appraisal.facultyName}</span>
            </h1>
            <p className="mt-0.5 text-sm text-muted">
              {appraisal.cadre} · {appraisal.department}
            </p>
          </div>
          <div className="flex flex-col items-start gap-2 sm:items-end">
            <StatusBadge status={appraisal.status} queryRaised={appraisal.queryRaised} />
            {isFaculty && <SaveStatus />}
          </div>
        </div>
        <PageRuler counts={counts} className="mt-3 max-w-md" />
      </div>

      {!appraisal.editable && (
        <Alert tone="info" title={isFaculty ? `Read-only: ${STATUS_INFO[appraisal.status].label}` : "Read-only review view"}>
          {isFaculty ? "This appraisal cannot be edited right now. You can still read everything." : "You are reviewing this appraisal. The faculty member's entries cannot be changed here."}
        </Alert>
      )}

      <div className="rise grid gap-6 xl:grid-cols-[16rem_minmax(0,1fr)]" style={{ "--i": 2 } as React.CSSProperties}>
        <aside>
          <SectionNav />
        </aside>
        <div className="min-w-0">{children}</div>
      </div>
    </div>
  );
}
