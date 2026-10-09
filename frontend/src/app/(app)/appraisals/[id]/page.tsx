"use client";

import { HistoryTimeline } from "@/components/appraisal/HistoryTimeline";
import { MessageComposer, MessagesFromHod } from "@/components/appraisal/Messages";
import { useAppraisal } from "@/components/appraisal/AppraisalProvider";
import { ReportDownload } from "@/components/appraisal/ReportDownload";
import { FocusOnArrival, RequirementsSummary } from "@/components/appraisal/RequirementsSummary";
import { ReviewPanel } from "@/components/appraisal/ReviewPanel";
import { ScoreSheet } from "@/components/appraisal/ScoreSheet";
import { SubmitPanel } from "@/components/appraisal/SubmitPanel";
import { useAuth } from "@/components/auth/AuthProvider";
import { Card, RuledHeading, SectionBar } from "@/components/ui/primitives";
import { LEVELS, isReviewer } from "@/lib/hierarchy";
import { formatDateTime } from "@/lib/format";
import { statusLine } from "@/lib/labels";

export default function ScoreAndReview() {
  const { appraisal } = useAppraisal();
  const { user } = useAuth();
  if (!appraisal || !user) return null;

  const isFaculty = user.role === "FACULTY";

  return (
    <div className="space-y-6">
      <header className="flex items-end gap-5">
        <span aria-hidden className="numeral-outline select-none text-7xl sm:text-8xl">12</span>
        <div className="pb-1">
          <p className="text-[11px] font-semibold uppercase tracking-[0.26em] text-brand">Part B · 11</p>
          <h2 className="font-display text-3xl font-medium tracking-tight text-navy"><span className="sr-only">12. </span>Score &amp; Review</h2>
        </div>
      </header>

      <FocusOnArrival />
      <RequirementsSummary />

      <Card className="space-y-3 p-4 sm:p-5">
        <RuledHeading>Where this appraisal stands</RuledHeading>
        <p className="font-display text-xl leading-snug">{statusLine(appraisal.status, appraisal.queryRaised, isFaculty)}</p>
        <dl className="grid gap-3 text-sm sm:grid-cols-3">
          <div><dt className="text-muted">Submitted</dt><dd className="font-medium">{appraisal.submittedAt ? formatDateTime(appraisal.submittedAt) : "Not yet"}</dd></div>
          <div><dt className="text-muted">Declaration</dt><dd className="font-medium">{appraisal.declaredAt ? formatDateTime(appraisal.declaredAt) : "Not yet made"}</dd></div>
          <div><dt className="text-muted">Approved</dt><dd className="font-medium">{appraisal.finalApprovedAt ? formatDateTime(appraisal.finalApprovedAt) : "—"}</dd></div>
        </dl>
      </Card>

      {isFaculty && <MessagesFromHod appraisalId={appraisal.id} prominent />}

      <Card className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
        <div className="max-w-xl">
          <h3 className="font-display text-lg font-medium text-navy">The printed form</h3>
          <p className="text-sm text-muted">
            {appraisal.status === "APPROVED"
              ? "The official Faculty Self Appraisal & Assessment Report, issued at approval. It is the same every time."
              : "A draft of the college's form with everything entered so far, marked DRAFT. The official copy is issued once the appraisal is approved."}
          </p>
        </div>
        <ReportDownload appraisalId={appraisal.id} status={appraisal.status} />
      </Card>

      <Card className="space-y-3 p-4 sm:p-5">
        <SectionBar>11. Self Appraisal Score Sheet</SectionBar>
        <ScoreSheet scores={appraisal.scores} cadre={appraisal.cadre} editable={isFaculty && appraisal.editable} />
      </Card>

      {isFaculty && appraisal.editable && (
        <Card className="space-y-3 p-4 sm:p-5">
          <SectionBar>Submit</SectionBar>
          <SubmitPanel />
        </Card>
      )}

      {isReviewer(user.role) && (
        <Card className="space-y-3 p-4 sm:p-5">
          <SectionBar>{user.role === "HOD" ? "HoD review" : `${LEVELS[user.role].title} review`}</SectionBar>
          <ReviewPanel role={user.role} />
        </Card>
      )}

      {user.role === "HOD" && <MessageComposer appraisalId={appraisal.id} status={appraisal.status} />}

      <Card className="space-y-3 p-4 sm:p-5">
        <RuledHeading>Review history</RuledHeading>
        <HistoryTimeline history={appraisal.history} forFaculty={isFaculty} />
      </Card>
    </div>
  );
}
