"use client";

import { useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { ApiError, post } from "@/lib/api";
import { LEVELS, type ReviewerRole } from "@/lib/hierarchy";
import { useAppraisal } from "./AppraisalProvider";

const MAX = 2000;

/**
 * Review actions for one level of the chain. Each level begins the review, may write a comment, and approves: the Head
 * of the Department's approval forwards the appraisal to the Principal or the Director Technical, and the approval of
 * either of those is final. There is no way to send an appraisal back. Labels follow the form's boxes ("Recommendations
 * of HoD", "Remarks of the Principal", "Remarks of the Director Technical").
 */
export function ReviewPanel({ role }: { role: ReviewerRole }) {
  const { id, appraisal, refreshAppraisal } = useAppraisal();
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmApprove, setConfirmApprove] = useState(false);

  if (!appraisal) return null;
  const level = LEVELS[role];
  const status = appraisal.status;
  const forwards = level.next !== null;

  const run = async (path: string, body?: unknown) => {
    setBusy(path);
    setError(null);
    try {
      await post(`/api/appraisals/${id}/review/${path}`, body);
      setComment("");
      await refreshAppraisal();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Something went wrong. Please try again.");
    } finally {
      setBusy(null);
      setConfirmApprove(false);
    }
  };

  if (status === level.waiting) {
    return (
      <div className="space-y-3">
        <p className="text-sm">This appraisal is waiting for you. Begin the review to read it and record your decision.</p>
        {error && <Alert tone="error" title="Could not begin">{error}</Alert>}
        <Button onClick={() => void run("start")} loading={busy === "start"}>Begin review</Button>
      </div>
    );
  }

  if (status === level.reviewing) {
    return (
      <div className="space-y-4">
        <div>
          <label htmlFor="review-comment" className="mb-1 block text-sm font-medium">{level.boxTitle}</label>
          <textarea id="review-comment" rows={5} maxLength={MAX} value={comment} onChange={(e) => setComment(e.target.value)} aria-describedby="review-help" className="block w-full rounded-sm border border-line-strong px-3 py-2 text-sm" />
          <p id="review-help" className="mt-1 text-xs text-muted">
            Optional. Your comment is recorded with your approval and printed in this box on the report. The faculty member cannot see it. {comment.length} / {MAX}
          </p>
          {role === "HOD" && (
            <p className="mt-1 text-xs text-muted">
              Not ready to approve? Use <span className="font-semibold">Message the faculty member</span> below to tell them and ask them to meet you. The appraisal stays with you until you approve it.
            </p>
          )}
        </div>
        {error && <Alert tone="error" title="Not done">{error}</Alert>}
        <Button onClick={() => setConfirmApprove(true)} loading={busy === "approve"}>
          {forwards ? `Approve and forward to the ${level.next}` : "Approve (final)"}
        </Button>

        <ConfirmDialog
          open={confirmApprove}
          title={forwards ? "Approve and forward this appraisal?" : "Approve this appraisal?"}
          confirmLabel={forwards ? "Yes, approve and forward" : "Yes, approve"}
          onCancel={() => setConfirmApprove(false)}
          onConfirm={() => void run("approve", { comment })}
          message={forwards
            ? <p>It will go to the {level.next}. You cannot change your comment or take it back afterwards.</p>
            : <p>Approval is final and the appraisal can no longer be changed. Continue?</p>}
        />
      </div>
    );
  }

  return <p className="text-sm text-muted">No action is needed from you at this stage.</p>;
}
