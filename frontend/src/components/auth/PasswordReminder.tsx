"use client";

import Link from "next/link";
import { useState } from "react";

const KEY = "fams.passwordReminderHidden";

/**
 * A temporary or reset password is still in use. This only recommends a change: nothing is blocked, the person can
 * dismiss it for this browser session, and Change password stays under Account at all times.
 */
export function PasswordReminder() {
  // Rendered only after the person is known, on the client, so reading the stored choice here cannot differ from the server's HTML.
  const [hidden, setHidden] = useState(() => {
    try {
      return window.sessionStorage.getItem(KEY) === "1";
    } catch {
      return false;
    }
  });

  if (hidden) return null;
  return (
    <div role="status" className="border-b border-warn/30 bg-warn-soft text-ink">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-4 gap-y-1 px-4 py-2.5 text-sm sm:px-6">
        <p className="min-w-0 flex-1">
          <span className="font-semibold">You are using a temporary password.</span> We recommend choosing one only you know.
        </p>
        <Link href="/account" className="font-semibold text-brand underline underline-offset-2">
          Change password
        </Link>
        <button
          type="button"
          className="rounded px-2 py-1 text-muted hover:text-ink"
          onClick={() => {
            setHidden(true);
            try {
              window.sessionStorage.setItem(KEY, "1");
            } catch {
              // the reminder simply returns on the next page load
            }
          }}
        >
          Remind me later
        </button>
      </div>
    </div>
  );
}
