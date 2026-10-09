"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, get, put } from "@/lib/api";
import { loadMetas } from "@/lib/meta";
import type { AppraisalView, Rec, SectionData, SectionMeta } from "@/lib/types";

export type SaveState = "idle" | "saving" | "saved" | "error";

export interface SectionState {
  data: SectionData | null;
  loading: boolean;
  error: string | null;
}

export type SaveResult = { ok: true; data: SectionData } | { ok: false; error: ApiError };

interface AppraisalContext {
  id: number;
  appraisal: AppraisalView | null;
  appraisalError: string | null;
  refreshAppraisal: () => Promise<void>;
  counts: Record<string, number>;
  metas: Record<string, SectionMeta> | null;
  metaError: string | null;
  section: (key: string) => SectionState;
  loadSection: (key: string) => void;
  /**
   * Saves a section. `mutate` receives the latest saved records when the save actually runs (saves to the same
   * section are queued one after another), so two views of one section can never overwrite each other.
   */
  saveSection: (key: string, mutate: (current: Rec[]) => Rec[]) => Promise<SaveResult>;
  saveState: SaveState;
  saveMessage: string | null;
  lastSavedAt: Date | null;
  setDirty: (key: string, dirty: boolean) => void;
  /** Re-runs the save that last failed (the user's entries are still on screen). */
  retryFailedSave: () => void;
}

const Ctx = createContext<AppraisalContext | null>(null);

const EMPTY: SectionState = { data: null, loading: false, error: null };

export function AppraisalProvider({ id, children }: { id: number; children: React.ReactNode }) {
  const [appraisal, setAppraisal] = useState<AppraisalView | null>(null);
  const [appraisalError, setAppraisalError] = useState<string | null>(null);
  const [counts, setCounts] = useState<Record<string, number>>({});
  const [metas, setMetas] = useState<Record<string, SectionMeta> | null>(null);
  const [metaError, setMetaError] = useState<string | null>(null);
  const [sections, setSections] = useState<Record<string, SectionState>>({});
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [saveMessage, setSaveMessage] = useState<string | null>(null);
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);

  // Latest values for use inside queued saves (state would be stale there).
  const latest = useRef<Record<string, SectionData | null>>({});
  const chains = useRef<Record<string, Promise<unknown>>>({});
  const requested = useRef<Set<string>>(new Set());
  const pending = useRef(0);
  const dirty = useRef<Set<string>>(new Set());
  const failed = useRef<{ key: string; mutate: (current: Rec[]) => Rec[] } | null>(null);

  const refreshAppraisal = useCallback(async () => {
    try {
      setAppraisal(await get<AppraisalView>(`/api/appraisals/${id}`));
      setAppraisalError(null);
    } catch (e) {
      setAppraisalError(e instanceof ApiError && e.status === 404 ? "This appraisal was not found, or you do not have access to it." : (e as Error).message);
    }
  }, [id]);

  useEffect(() => {
    let alive = true;
    get<AppraisalView>(`/api/appraisals/${id}`)
      .then((a) => alive && setAppraisal(a))
      .catch((e) => {
        if (alive) setAppraisalError(e instanceof ApiError && e.status === 404 ? "This appraisal was not found, or you do not have access to it." : (e as Error).message);
      });
    get<Record<string, number>>(`/api/appraisals/${id}/sections`)
      .then((c) => alive && setCounts(c))
      .catch(() => {});
    loadMetas()
      .then((m) => alive && setMetas(m))
      .catch((e) => alive && setMetaError((e as Error).message));
    return () => {
      alive = false;
    };
  }, [id]);

  const setSection = useCallback((key: string, state: SectionState) => {
    latest.current[key] = state.data;
    setSections((s) => ({ ...s, [key]: state }));
  }, []);

  const loadSection = useCallback(
    (key: string) => {
      if (requested.current.has(key)) return;
      requested.current.add(key);
      setSection(key, { data: null, loading: true, error: null });
      get<SectionData>(`/api/appraisals/${id}/sections/${key}`)
        .then((data) => setSection(key, { data, loading: false, error: null }))
        .catch((e) => {
          requested.current.delete(key); // allow retry
          setSection(key, { data: null, loading: false, error: (e as Error).message });
        });
    },
    [id, setSection],
  );

  const saveSection = useCallback(
    (key: string, mutate: (current: Rec[]) => Rec[]): Promise<SaveResult> => {
      const run = async (): Promise<SaveResult> => {
        const current = latest.current[key]?.records ?? [];
        const next = mutate(current);
        try {
          const data = await put<SectionData>(`/api/appraisals/${id}/sections/${key}`, { records: next });
          setSection(key, { data, loading: false, error: null });
          setCounts((c) => ({ ...c, [key]: data.records.length }));
          void refreshAppraisal(); // the marks calculated from the entries follow what was just saved
          pending.current -= 1;
          if (failed.current?.key === key) failed.current = null;
          if (pending.current === 0) {
            setSaveState("saved");
            setSaveMessage(null);
            setLastSavedAt(new Date());
          }
          return { ok: true, data };
        } catch (e) {
          const error = e instanceof ApiError ? e : new ApiError(0, (e as Error).message);
          pending.current -= 1;
          failed.current = { key, mutate };
          setSaveState("error");
          setSaveMessage(error.message);
          if (error.status === 409) void refreshAppraisal(); // probably locked meanwhile
          return { ok: false, error };
        }
      };
      pending.current += 1;
      setSaveState("saving");
      const prior = chains.current[key] ?? Promise.resolve();
      const task = prior.then(run, run);
      chains.current[key] = task.catch(() => undefined);
      return task;
    },
    [id, refreshAppraisal, setSection],
  );

  const retryFailedSave = useCallback(() => {
    const f = failed.current;
    if (f) void saveSection(f.key, f.mutate);
  }, [saveSection]);

  const setDirty = useCallback((key: string, isDirty: boolean) => {
    if (isDirty) dirty.current.add(key);
    else dirty.current.delete(key);
  }, []);

  // Warn before leaving with unsaved or still-saving changes.
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (pending.current > 0 || dirty.current.size > 0) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, []);

  const value = useMemo<AppraisalContext>(
    () => ({
      id,
      appraisal,
      appraisalError,
      refreshAppraisal,
      counts,
      metas,
      metaError,
      section: (key) => sections[key] ?? EMPTY,
      loadSection,
      saveSection,
      saveState,
      saveMessage,
      lastSavedAt,
      setDirty,
      retryFailedSave,
    }),
    [id, appraisal, appraisalError, refreshAppraisal, counts, metas, metaError, sections, loadSection, saveSection, saveState, saveMessage, lastSavedAt, setDirty, retryFailedSave],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAppraisal(): AppraisalContext {
  const c = useContext(Ctx);
  if (!c) throw new Error("useAppraisal must be used inside AppraisalProvider");
  return c;
}
