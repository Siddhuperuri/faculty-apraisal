"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/primitives";
import { get } from "@/lib/api";
import { useAuth } from "./AuthProvider";

/** A session ends after this long without any activity; the server enforces the same 30 minutes. */
export const IDLE_LIMIT_MS = 30 * 60 * 1000;
/** The person is warned this long before it ends. */
export const WARNING_MS = 2 * 60 * 1000;

const ACTIVITY = ["pointerdown", "keydown", "scroll", "touchstart"] as const;

/**
 * Ends the session after 30 minutes of inactivity, and warns two minutes before. Pressing a key or touching the page
 * counts as activity; the warning offers "Stay signed in" (which asks the server for a fresh session timer). At the end
 * the person is taken to the sign-in page. Anything already saved is on the server, and what was typed into an open form
 * but not yet saved is kept by the form itself (see lib/draftStore) and offered again after signing in.
 */
export function SessionTimeout() {
  const { user, logout } = useAuth();
  const last = useRef(0);   // set when the person signs in (see the effect)
  const [remaining, setRemaining] = useState<number | null>(null);

  useEffect(() => {
    if (!user) return;
    last.current = Date.now();
    const touch = () => {
      // activity during the warning does not silently dismiss it: the person must choose
      if (Date.now() - last.current < IDLE_LIMIT_MS - WARNING_MS) last.current = Date.now();
    };
    for (const e of ACTIVITY) window.addEventListener(e, touch, { passive: true });
    const timer = window.setInterval(() => {
      const idle = Date.now() - last.current;
      if (idle >= IDLE_LIMIT_MS) {
        window.clearInterval(timer);
        void (async () => {
          try {
            await logout();
          } finally {
            window.location.replace("/login?expired=1");
          }
        })();
      } else if (idle >= IDLE_LIMIT_MS - WARNING_MS) {
        setRemaining(Math.ceil((IDLE_LIMIT_MS - idle) / 1000));
      } else {
        setRemaining(null);
      }
    }, 1000);
    return () => {
      window.clearInterval(timer);
      for (const e of ACTIVITY) window.removeEventListener(e, touch);
    };
  }, [user, logout]);

  if (!user || remaining === null) return null;

  const stay = async () => {
    last.current = Date.now();
    setRemaining(null);
    try {
      await get("/api/auth/me"); // any request renews the server's timer
    } catch {
      // a 401 is handled centrally (sign-in page); anything else will be seen on the next action
    }
  };

  const minutes = Math.floor(remaining / 60);
  const seconds = String(remaining % 60).padStart(2, "0");
  return (
    <div role="alertdialog" aria-live="assertive" aria-labelledby="session-warning-title"
      className="fixed inset-x-0 bottom-4 z-50 mx-auto w-[min(28rem,calc(100%-2rem))] rounded-lg border border-line-strong bg-surface p-4 shadow-lg">
      <h2 id="session-warning-title" className="font-display text-lg font-medium text-navy">You are about to be signed out</h2>
      <p className="mt-1 text-sm">
        There has been no activity for a while. For your security you will be signed out in{" "}
        <span className="font-semibold tabular-nums">{minutes}:{seconds}</span>. Anything you have saved is kept.
      </p>
      <div className="mt-3 flex justify-end">
        <Button onClick={() => void stay()}>Stay signed in</Button>
      </div>
    </div>
  );
}
