"use client";

import { useEffect, useRef, useState } from "react";
import { Alert, Button, Card, SectionBar } from "@/components/ui/primitives";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { ApiError, get, post, put } from "@/lib/api";
import { useAppraisal } from "./AppraisalProvider";
import { formatDateTime } from "@/lib/format";
import type { AppraisalMessage, Status } from "@/lib/types";

const MAX = 2000;

/** The standard request, for a Head of the Department who wants the faculty member to come and talk. */
const ASK_TO_MEET =
  "Please come and see me about your appraisal. I have a query about it that we need to discuss in person before I can approve it. Let me know a time that suits you.";

/** The messages about one appraisal, oldest first; `reload` fetches them again. */
function useMessages(appraisalId: number) {
  const [messages, setMessages] = useState<AppraisalMessage[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [round, setRound] = useState(0);

  useEffect(() => {
    let alive = true;
    get<AppraisalMessage[]>(`/api/appraisals/${appraisalId}/messages`)
      .then((m) => {
        if (!alive) return;
        setMessages(m);
        setError(null);
      })
      .catch((e) => alive && setError((e as Error).message));
    return () => {
      alive = false;
    };
  }, [appraisalId, round]);

  return { messages, error, reload: () => setRound((r) => r + 1) };
}

function sender(m: AppraisalMessage): string {
  return m.senderName ? `${m.senderName}, Head of the Department` : "Your Head of the Department";
}

/**
 * What the Head of the Department has written to the faculty member about this appraisal, with a plain request to
 * arrange a meeting. Shows nothing when there is no message. Opening it marks the messages as seen, which the Head of
 * the Department can then see; the "New" tags stay for this visit so the reader can tell what has just arrived.
 */
export function MessagesFromHod({ appraisalId, prominent = false }: { appraisalId: number; prominent?: boolean }) {
  const { messages } = useMessages(appraisalId);
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const marked = useRef(false);

  useEffect(() => {
    if (!messages || marked.current) return;
    const unseen = messages.filter((m) => m.readAt === null).map((m) => m.id);
    if (unseen.length === 0) return;
    marked.current = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- remembers which messages were new when the page opened
    setFresh(new Set(unseen));
    post(`/api/appraisals/${appraisalId}/messages/read`).catch(() => {
      marked.current = false;   // try again on the next visit; the message is still shown
    });
  }, [messages, appraisalId]);

  if (!messages || messages.length === 0) return null;
  return (
    <section aria-labelledby="hod-messages" className={`space-y-3 rounded-xl border p-4 sm:p-5 ${prominent ? "border-warn/40 bg-warn-soft shadow-[var(--shadow-paper)]" : "border-line bg-surface shadow-[var(--shadow-paper)]"}`}>
      <div>
        <h2 id="hod-messages" className="font-display text-xl font-medium text-navy">
          {messages.length === 1 ? "A message from your Head of the Department" : `${messages.length} messages from your Head of the Department`}
        </h2>
        <p className="mt-1 text-sm text-muted">
          Please arrange to meet them about the query. Your appraisal is with them and cannot be changed in the meantime.
        </p>
      </div>
      <ul className="space-y-3">
        {messages.map((m) => (
          <li key={m.id} className="rounded-lg border border-line bg-surface p-3.5">
            <p className="flex flex-wrap items-center gap-2 text-xs text-muted">
              <span className="font-semibold text-ink">{sender(m)}</span>
              {m.editedAt && <span>· Edited</span>}
              {fresh.has(m.id) && <span className="rounded-full bg-fill px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-white">{m.editedAt ? "Updated" : "New"}</span>}
            </p>
            <p className="mt-1.5 whitespace-pre-wrap break-words text-[15px] leading-relaxed">{m.body}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * The Head of the Department's side: write to the faculty member while reviewing (after beginning, before approving),
 * and see what has been sent and whether it has been seen. Nothing here changes the appraisal.
 */
export function MessageComposer({ appraisalId, status }: { appraisalId: number; status: Status }) {
  const { messages, error, reload } = useMessages(appraisalId);
  const { refreshAppraisal } = useAppraisal();   // sending a message changes the status shown in the page header
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [sent, setSent] = useState(false);

  const canSend = status === "HOD_REVIEW";
  const any = (messages?.length ?? 0) > 0;
  // Once the review is over and nothing was said, there is nothing to show.
  if (!canSend && !any && status !== "SUBMITTED") return null;

  const send = async () => {
    setBusy(true);
    setProblem(null);
    try {
      await post(`/api/appraisals/${appraisalId}/messages`, { message: text });
      setText("");
      setSent(true);
      reload();
      void refreshAppraisal();
    } catch (e) {
      setProblem(e instanceof ApiError ? (e.fieldErrors?.message ?? e.message) : "Could not send the message. Please try again.");
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <Card className="space-y-4 p-4 sm:p-5">
      <SectionBar>Message the faculty member</SectionBar>
      {!canSend && status === "SUBMITTED" && (
        <p className="text-sm text-muted">Begin the review to be able to message the faculty member about it.</p>
      )}
      {error && <Alert tone="error" title="Could not load the messages">{error}</Alert>}

      {any && (
        <ul className="space-y-3" aria-label="Messages you have sent">
          {messages!.map((m) => (
            <SentMessage key={m.id} appraisalId={appraisalId} m={m} canEdit={canSend && m.mine} onChanged={reload} />
          ))}
        </ul>
      )}

      {canSend && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (text.trim()) setConfirming(true);
          }}
          className="space-y-3"
          noValidate
        >
          {sent && !problem && <Alert tone="ok" title="Message sent">The faculty member will see it the next time they open the website.</Alert>}
          {problem && <Alert tone="error" title="Not sent">{problem}</Alert>}
          <div>
            <label htmlFor="hod-message" className="mb-1 block text-sm font-medium">Message to the faculty member</label>
            <textarea
              id="hod-message"
              rows={4}
              maxLength={MAX}
              value={text}
              onChange={(e) => setText(e.target.value)}
              aria-describedby="hod-message-help"
              className="block w-full rounded-sm border border-line-strong px-3 py-2 text-sm"
              placeholder="For example: I have a question about your teaching load. Please come and see me this week."
            />
            <p id="hod-message-help" className="mt-1 text-xs text-muted">
              They are shown this on their dashboard, with a request to meet you. The appraisal stays with you and stays locked. You can edit a message until you
              approve the appraisal (they are shown that it was edited), but not delete it. The comment you write when you approve is separate, and they cannot see it. {text.length} / {MAX}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" loading={busy} disabled={!text.trim()}>Send message</Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setText(ASK_TO_MEET)}>Use a standard request to meet</Button>
          </div>
        </form>
      )}

      <ConfirmDialog
        open={confirming}
        title="Send this message?"
        confirmLabel="Send message"
        onCancel={() => setConfirming(false)}
        onConfirm={() => void send()}
        message={
          <div className="space-y-2">
            <p>The faculty member will see it on their dashboard. You can still edit it until you approve the appraisal.</p>
            <blockquote className="whitespace-pre-wrap break-words rounded-sm border-l-2 border-ochre bg-canvas/80 px-3 py-2 text-sm">{text.trim()}</blockquote>
          </div>
        }
      />
    </Card>
  );
}

/** One message the Head of the Department has sent, with whether it was seen, and Edit while the review is still open. */
function SentMessage({ appraisalId, m, canEdit, onChanged }: { appraisalId: number; m: AppraisalMessage; canEdit: boolean; onChanged: () => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(m.body);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setProblem(null);
    try {
      await put(`/api/appraisals/${appraisalId}/messages/${m.id}`, { message: draft });
      setEditing(false);
      onChanged();
    } catch (e) {
      setProblem(e instanceof ApiError ? (e.fieldErrors?.message ?? e.message) : "Could not save the change. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <li className="rounded-lg border border-line bg-canvas/70 p-3.5">
      <p className="flex flex-wrap items-center gap-2 text-xs text-muted">
        <span>Sent {formatDateTime(m.sentAt)}</span>
        {m.editedAt && <span>· Edited {formatDateTime(m.editedAt)}</span>}
        <span className={m.readAt ? "font-semibold text-ok" : "font-semibold text-warn"}>
          {m.readAt ? `· Seen ${formatDateTime(m.readAt)}` : "· Not yet seen"}
        </span>
      </p>
      {editing ? (
        <div className="mt-2 space-y-2">
          {problem && <Alert tone="error" title="Not saved">{problem}</Alert>}
          <label htmlFor={`edit-message-${m.id}`} className="sr-only">Edit the message</label>
          <textarea
            id={`edit-message-${m.id}`}
            rows={4}
            maxLength={MAX}
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="block w-full rounded-sm border border-line-strong px-3 py-2 text-sm"
          />
          <p className="text-xs text-muted">The faculty member is shown the new wording as an update, marked as edited. The earlier wording is kept for the record, and only Heads of the Department can see it. {draft.length} / {MAX}</p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" loading={busy} disabled={!draft.trim()} onClick={() => void save()}>Save change</Button>
            <Button size="sm" variant="secondary" onClick={() => setEditing(false)}>Cancel</Button>
          </div>
        </div>
      ) : (
        <>
          <p className="mt-1.5 whitespace-pre-wrap break-words text-[15px] leading-relaxed">{m.body}</p>
          {m.earlier.length > 0 && (
            <details className="mt-2 rounded-sm border border-line bg-surface/70 px-3 py-2 text-sm">
              <summary className="cursor-pointer text-xs font-semibold text-brand">
                Earlier wording ({m.earlier.length}) <span className="font-normal text-muted">· kept for the record; the faculty member cannot see it</span>
              </summary>
              <ol className="mt-2 space-y-2">
                {m.earlier.map((v, i) => (
                  <li key={i} className="border-l-2 border-line-strong pl-3">
                    <p className="text-xs text-muted">Written {formatDateTime(v.writtenAt)} · replaced {formatDateTime(v.replacedAt)}</p>
                    <p className="mt-0.5 whitespace-pre-wrap break-words text-[14px] leading-relaxed text-ink/90">{v.body}</p>
                  </li>
                ))}
              </ol>
            </details>
          )}
          {canEdit && (
            <div className="mt-2">
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  setDraft(m.body);
                  setProblem(null);
                  setEditing(true);
                }}
              >
                Edit<span className="sr-only"> this message</span>
              </Button>
            </div>
          )}
        </>
      )}
    </li>
  );
}
