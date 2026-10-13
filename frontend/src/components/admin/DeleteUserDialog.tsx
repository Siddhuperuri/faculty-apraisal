"use client";

import { useEffect, useState } from "react";
import { Dialog } from "@/components/ui/Dialog";
import { Alert, Button, Skeleton } from "@/components/ui/primitives";
import { ApiError, get, post } from "@/lib/api";
import { confirmationMatches, deletionResultMessage, deletionWarning } from "@/lib/deleteUser";
import { roleLabel } from "@/lib/labels";
import type { AdminUserRow, DeletionPreview, DeletionResult } from "@/lib/types";

/**
 * "Delete User Permanently". Asks the server what would be removed and kept, says it with the person's name, e-mail address
 * and role, and deletes only once their e-mail address has been typed. The server checks the address again and refuses the
 * signed-in administrator's own account and the only active administrator.
 */
export function DeleteUserDialog({
  user,
  onClose,
  onDeleted,
}: {
  user: AdminUserRow | null;
  onClose: () => void;
  onDeleted: (message: string, id: number) => void;
}) {
  return (
    <Dialog open={user !== null} onClose={onClose} title="Delete user permanently">
      {user && <Body key={user.id} user={user} onClose={onClose} onDeleted={onDeleted} />}
    </Dialog>
  );
}

function Body({ user, onClose, onDeleted }: { user: AdminUserRow; onClose: () => void; onDeleted: (m: string, id: number) => void }) {
  const [preview, setPreview] = useState<DeletionPreview | null>(null);
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    get<DeletionPreview>(`/api/admin/users/${user.id}/deletion-preview`)
      .then((p) => {
        if (live) setPreview(p);
      })
      .catch((e) => {
        if (live) setError(e instanceof ApiError ? e.message : "Could not check this user. Please try again.");
      });
    return () => {
      live = false;
    };
  }, [user.id]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!preview || !confirmationMatches(preview.email, typed)) return;
    setBusy(true);
    setError(null);
    setProblem(null);
    try {
      const result = await post<DeletionResult>(`/api/admin/users/${user.id}/delete-permanently`, { confirmEmail: typed.trim() });
      onDeleted(deletionResultMessage(preview.email, result), user.id);
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.fieldErrors?.confirmEmail) setProblem(err.fieldErrors.confirmEmail);
      setError(err instanceof ApiError ? err.message : "Something went wrong. Nothing was deleted.");
      setBusy(false);
    }
  };

  if (!preview) {
    return error ? (
      <div className="space-y-4">
        <Alert tone="error" title="Could not check this user">{error}</Alert>
        <div className="flex justify-end"><Button variant="secondary" onClick={onClose}>Close</Button></div>
      </div>
    ) : (
      <div aria-busy="true" aria-label="Checking what would be removed"><Skeleton className="h-40 w-full" /></div>
    );
  }

  if (!preview.canDelete) {
    return (
      <div className="space-y-4">
        <Alert tone="error" title="This user cannot be deleted">{preview.blockedReason}</Alert>
        <div className="flex justify-end"><Button variant="secondary" onClick={onClose}>Close</Button></div>
      </div>
    );
  }

  const warning = deletionWarning(preview, roleLabel(preview.role));
  const ready = confirmationMatches(preview.email, typed);
  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <div role="alert" className="rounded-md border border-bad px-4 py-3">
        <p className="font-semibold text-bad" data-delete-headline>{warning.headline}</p>
        <dl className="mt-2 grid gap-1 text-sm sm:grid-cols-[7rem_1fr]">
          <dt className="text-muted">Name</dt>
          <dd>{preview.name ?? "—"}</dd>
          <dt className="text-muted">E-mail</dt>
          <dd className="break-all">{preview.email}</dd>
          <dt className="text-muted">Role</dt>
          <dd>{roleLabel(preview.role)}</dd>
        </dl>
      </div>
      <section aria-label="What is removed">
        <h3 className="text-sm font-semibold">What is removed</h3>
        <ul className="mt-1 list-disc space-y-1 pl-5 text-sm">{warning.removes.map((t) => <li key={t}>{t}</li>)}</ul>
      </section>
      <section aria-label="What is kept">
        <h3 className="text-sm font-semibold">What is kept, without the person</h3>
        <ul className="mt-1 list-disc space-y-1 pl-5 text-sm">{warning.keeps.map((t) => <li key={t}>{t}</li>)}</ul>
      </section>
      <ul className="space-y-1 text-sm font-medium text-bad">{warning.consequences.map((t) => <li key={t}>{t}</li>)}</ul>

      <div>
        <label htmlFor="delete-confirm" className="mb-1 block text-[13px] font-semibold">
          Type <span className="font-mono">{preview.email}</span> to confirm
        </label>
        <input
          id="delete-confirm"
          type="text"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          autoComplete="off"
          spellCheck={false}
          aria-invalid={problem ? true : undefined}
          aria-describedby={problem ? "delete-confirm-err" : undefined}
          className="block w-full rounded-sm border border-line-strong bg-surface px-3 py-2 text-[15px]"
        />
        {problem && <p id="delete-confirm-err" className="mt-1 text-sm font-medium text-bad">{problem}</p>}
      </div>
      {error && !problem && <Alert tone="error" title="Not deleted">{error}</Alert>}
      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
        <Button type="submit" variant="danger" loading={busy} disabled={!ready}>Delete permanently</Button>
      </div>
    </form>
  );
}
