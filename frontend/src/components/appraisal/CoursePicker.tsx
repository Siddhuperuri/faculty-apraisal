"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Button } from "@/components/ui/primitives";
import { ApiError, get } from "@/lib/api";

interface EcapCourse {
  code: string;
  name: string;
}

const CONTROL =
  "block w-full rounded-sm border bg-surface px-3 py-2 text-[15px] text-ink shadow-[inset_0_1px_2px_rgb(20_30_54/0.06)] focus:border-brand";

/**
 * Chooses a course from ECAP, the college's academic system: the code and the name are ECAP's and cannot be typed.
 * Type two or more characters of a course code or name to search; pick one from the list. When ECAP cannot be reached the
 * page says so and offers to try again; nothing is saved with a course that was not chosen from ECAP.
 */
export function CoursePicker({
  code,
  name,
  onPick,
  error,
  disabled = false,
}: {
  code: string;
  name: string;
  onPick: (course: EcapCourse | null) => void;
  error?: string;
  disabled?: boolean;
}) {
  const uid = useId();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<EcapCourse[]>([]);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [changing, setChanging] = useState(false);
  const latest = useRef(0);

  const chosen = code !== "" && !changing;

  useEffect(() => {
    if (chosen) return;
    const q = query.trim();
    if (q.length < 2) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clearing results when the search text is too short
      setResults([]);
      setFailure(null);
      return;
    }
    const ticket = ++latest.current;
    setLoading(true);
    const timer = window.setTimeout(() => {
      get<{ courses: EcapCourse[] }>(`/api/ecap/courses?q=${encodeURIComponent(q)}`)
        .then((r) => {
          if (ticket !== latest.current) return;
          setResults(r.courses);
          setFailure(null);
        })
        .catch((e) => {
          if (ticket !== latest.current) return;
          setResults([]);
          setFailure(e instanceof ApiError ? e.message : "ECAP is not available right now. Please try again in a few minutes.");
        })
        .finally(() => {
          if (ticket === latest.current) setLoading(false);
        });
    }, 300);
    return () => window.clearTimeout(timer);
  }, [query, chosen, attempt]);

  const border = error ? "border-bad" : "border-line-strong";

  return (
    <div>
      <label htmlFor={`${uid}-search`} className="mb-1 block text-[13px] font-semibold tracking-wide">
        Course (from ECAP)
        <span aria-hidden className="ml-0.5 text-bad">*</span>
        <span className="sr-only"> (required)</span>
      </label>

      {chosen ? (
        <div className={`flex flex-wrap items-center justify-between gap-3 rounded-sm border bg-canvas px-3 py-2 ${border}`}>
          <p className="text-[15px]">
            <span className="font-semibold tabular-nums">{code}</span> · {name}
          </p>
          {!disabled && (
            <Button type="button" variant="ghost" size="sm" onClick={() => { setChanging(true); setQuery(""); }}>
              Change course
            </Button>
          )}
        </div>
      ) : (
        <div>
          <input
            id={`${uid}-search`}
            type="search"
            autoComplete="off"
            value={query}
            disabled={disabled}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search by course code or name"
            aria-describedby={`${uid}-help`}
            aria-invalid={error ? true : undefined}
            className={`${CONTROL} ${border}`}
          />
          <p id={`${uid}-help`} className="mt-1 text-xs text-muted">
            Type at least two characters, then choose the course from the list. Course code and name come from ECAP and cannot be typed.
          </p>
          {loading && <p role="status" className="mt-1 text-sm text-muted">Searching ECAP…</p>}
          {failure && (
            <p role="alert" className="mt-1 text-sm font-medium text-bad">
              {failure}{" "}
              <button type="button" className="underline" onClick={() => setAttempt((n) => n + 1)}>Try again</button>
            </p>
          )}
          {!loading && !failure && query.trim().length >= 2 && results.length === 0 && (
            <p className="mt-1 text-sm text-muted">No course in ECAP matches “{query.trim()}”.</p>
          )}
          {results.length > 0 && (
            <ul role="listbox" aria-label="ECAP courses" className="mt-1 max-h-56 overflow-auto rounded-sm border border-line-strong bg-surface">
              {results.map((c) => (
                <li key={c.code} role="option" aria-selected={false}>
                  <button
                    type="button"
                    className="block w-full px-3 py-2 text-left text-sm hover:bg-brand-soft"
                    onClick={() => {
                      onPick(c);
                      setChanging(false);
                      setResults([]);
                    }}
                  >
                    <span className="font-semibold tabular-nums">{c.code}</span> · {c.name}
                  </button>
                </li>
              ))}
            </ul>
          )}
          {code !== "" && changing && (
            <p className="mt-1 text-xs text-muted">
              Current course: {code} · {name}.{" "}
              <button type="button" className="underline" onClick={() => setChanging(false)}>Keep it</button>
            </p>
          )}
        </div>
      )}
      {error && <p className="mt-1 text-sm font-medium text-bad">{error}</p>}
    </div>
  );
}
