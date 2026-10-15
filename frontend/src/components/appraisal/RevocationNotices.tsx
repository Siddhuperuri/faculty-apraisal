"use client";

import { useEffect, useState } from "react";
import { Alert, Card, RuledHeading } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { roleLabel } from "@/lib/labels";
import type { AppraisalView, RevocationTrail, Role, RevokeNotice, TrailEntry } from "@/lib/types";

const ENTRY_LABEL: Record<TrailEntry["action"], string> = {
  SUBMIT: "Submitted by the faculty member",
  RESUBMIT: "Corrected and sent again by the faculty member",
  HOD_REVOKE: "Revoked by the Head of the Department, returned to the faculty member",
  PRINCIPAL_REVOKE: "Revoked by the Principal, returned to the Head of the Department",
  DIRECTOR_REVOKE: "Revoked by the Director Technical, returned to the Head of the Department",
};

/**
 * Tells the reader why the appraisal is with them again: the faculty member is shown the HoD's remarks when it was revoked
 * (they are the only ones who see them), and the HoD is shown the remarks of the Principal or the Director Technical who sent
 * it back. Nothing is shown to anyone else, and nothing when the appraisal was not revoked.
 */
export function ReturnedNotice({ appraisal, role }: { appraisal: AppraisalView; role: Role | undefined }) {
  const n = appraisal.returnedNotice;
  if (!n) return null;
  if (role === "FACULTY") {
    return (
      <Alert tone="warn" title="Your HoD has revoked this appraisal">
        <NoticeBody notice={n} intro="Please correct it as asked and submit it again. Your HoD wrote:" />
      </Alert>
    );
  }
  if (role === "HOD") {
    return (
      <Alert tone="warn" title={`The ${roleLabel(n.byRole)} has sent this appraisal back to you`}>
        <NoticeBody notice={n} intro="Please review it again, then forward it again or return it to the faculty member. They wrote:" />
      </Alert>
    );
  }
  return null;
}

function NoticeBody({ notice, intro }: { notice: RevokeNotice; intro: string }) {
  return (
    <div className="space-y-2">
      <p>{intro}</p>
      <blockquote className="whitespace-pre-wrap rounded-sm border-l-2 border-ochre bg-surface/70 px-3 py-2 font-display text-[15px] italic">
        {notice.remarks || "No remarks were given."}
      </blockquote>
      <p className="text-xs text-muted">{formatDateTime(notice.at)}</p>
    </div>
  );
}

/** Every time the HoD revoked it, with the remarks, for the faculty member (not what any other reviewer wrote). */
export function RevocationsForFaculty({ appraisal }: { appraisal: AppraisalView }) {
  const list = appraisal.revocations ?? [];
  if (list.length === 0) return null;
  return (
    <Card className="space-y-3 p-4 sm:p-5">
      <RuledHeading>Revoked by your HoD</RuledHeading>
      <ol className="space-y-3">
        {list.map((r, i) => (
          <li key={i} className="border-l-2 border-line-strong pl-4">
            <p className="text-xs text-muted">{formatDateTime(r.at)}</p>
            <p className="mt-0.5 whitespace-pre-wrap text-sm">{r.remarks || "No remarks were given."}</p>
          </li>
        ))}
      </ol>
    </Card>
  );
}

/**
 * The HoD's whole trail for one appraisal: how many times it has been revoked and sent again, and every step with who
 * took it and what they wrote. It reads from its own endpoint, so it works for an appraisal that is a draft again.
 */
export function RevocationTrailCard({ appraisalId, heading = "Revocations and resubmissions" }: { appraisalId: number; heading?: string }) {
  const [trail, setTrail] = useState<RevocationTrail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    get<RevocationTrail>(`/api/appraisals/${appraisalId}/revocations`)
      .then((t) => alive && setTrail(t))
      .catch((e) => alive && setError((e as Error).message));
    return () => {
      alive = false;
    };
  }, [appraisalId]);

  return (
    <Card className="space-y-3 p-4 sm:p-5">
      <RuledHeading>{heading}</RuledHeading>
      {error && <p className="text-sm text-bad">{error}</p>}
      {!trail && !error && <p className="text-sm text-muted">Loading…</p>}
      {trail && <TrailBody trail={trail} />}
    </Card>
  );
}

export function TrailBody({ trail }: { trail: RevocationTrail }) {
  return (
    <>
      <dl className="grid grid-cols-2 gap-3 text-sm">
        <div className="rounded-lg border border-line bg-canvas/60 px-3 py-2">
          <dt className="text-muted">Times revoked</dt>
          <dd className="font-display text-2xl font-medium tabular-nums text-navy">{trail.timesRevoked}</dd>
        </div>
        <div className="rounded-lg border border-line bg-canvas/60 px-3 py-2">
          <dt className="text-muted">Times sent again</dt>
          <dd className="font-display text-2xl font-medium tabular-nums text-navy">{trail.timesResent}</dd>
        </div>
      </dl>
      {trail.entries.length === 0 ? (
        <p className="text-sm text-muted">Nothing has been submitted, revoked or sent again yet.</p>
      ) : (
        <ol className="space-y-3">
          {trail.entries.map((e, i) => (
            <li key={i} className="relative border-l-2 border-line-strong pl-5 before:absolute before:-left-[7px] before:top-1.5 before:h-3 before:w-3 before:rounded-full before:border-2 before:border-ochre before:bg-surface">
              <p className="font-display text-base font-medium">{ENTRY_LABEL[e.action] ?? e.action}</p>
              <p className="text-xs text-muted">{`${e.actorName ?? roleLabel(e.actorRole)} · ${formatDateTime(e.at)}`}</p>
              {e.remarks && <blockquote className="mt-1 whitespace-pre-wrap rounded-sm border-l-2 border-ochre bg-canvas/80 px-3 py-2 font-display text-[15px] italic">{e.remarks}</blockquote>}
            </li>
          ))}
        </ol>
      )}
    </>
  );
}
