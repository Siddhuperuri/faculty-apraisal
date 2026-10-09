"use client";

import { useSyncExternalStore } from "react";

const KEY = "fams-theme";
const CHANGED = "fams-theme-changed";

/** Runs in the page head before first paint: the saved choice, else the system's, goes on <html data-theme>. */
export const THEME_SCRIPT = `try{var t=localStorage.getItem("${KEY}");if(t!=="light"&&t!=="dark")t=matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light";document.documentElement.dataset.theme=t}catch(e){}`;

type Theme = "light" | "dark";

function current(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

function stored(): Theme | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === "light" || v === "dark" ? v : null;
  } catch {
    return null;
  }
}

function subscribe(notify: () => void) {
  // Until the person chooses, the page follows the system setting as it changes.
  const media = matchMedia("(prefers-color-scheme: dark)");
  const follow = () => {
    if (stored() === null) {
      document.documentElement.dataset.theme = media.matches ? "dark" : "light";
      notify();
    }
  };
  media.addEventListener("change", follow);
  window.addEventListener(CHANGED, notify);
  return () => {
    media.removeEventListener("change", follow);
    window.removeEventListener(CHANGED, notify);
  };
}

/** A sun/moon button that switches between the light and dark themes and remembers the choice in this browser. */
export function ThemeToggle({ className = "" }: { className?: string }) {
  const theme = useSyncExternalStore(subscribe, current, () => "light" as Theme);
  const dark = theme === "dark";

  const toggle = () => {
    const next: Theme = dark ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem(KEY, next);
    } catch {
      // Storage can be blocked (private window); the theme still changes for this visit.
    }
    window.dispatchEvent(new Event(CHANGED));
  };

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={dark ? "Switch to light mode" : "Switch to dark mode"}
      title={dark ? "Switch to light mode" : "Switch to dark mode"}
      className={`inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-full transition-colors ${className}`}
    >
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        {dark ? (
          <>
            <circle cx="12" cy="12" r="4" />
            <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
          </>
        ) : (
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
        )}
      </svg>
    </button>
  );
}
