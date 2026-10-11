/**
 * What a person has typed into an open entry form but not yet saved is kept in this tab's session storage, so that if the
 * session ends (or the page is reloaded) the entry can be offered again after signing in. It stays on this computer, in
 * this tab, is cleared when the entry is saved or cancelled, and is keyed by the signed-in person so another person
 * signing in on the same tab never sees it. Everything is best-effort: storage can be unavailable (private windows).
 */
const PREFIX = "fams-draft:";

export function draftKey(userId: number, appraisalId: number, section: string, mode: string, recordId: number | undefined): string {
  return `${PREFIX}${userId}:${appraisalId}:${section}:${mode}:${recordId ?? "new"}`;
}

export function loadDraft(key: string): Record<string, string> | null {
  try {
    const raw = window.sessionStorage.getItem(key);
    if (!raw) return null;
    const value = JSON.parse(raw) as unknown;
    if (value && typeof value === "object" && !Array.isArray(value)) {
      const out: Record<string, string> = {};
      for (const [k, v] of Object.entries(value)) if (typeof v === "string") out[k] = v;
      return out;
    }
  } catch {
    // unreadable or unavailable: no draft
  }
  return null;
}

export function saveDraft(key: string, values: Record<string, string>): void {
  try {
    window.sessionStorage.setItem(key, JSON.stringify(values));
  } catch {
    // storage full or unavailable: the form still works
  }
}

export function clearDraft(key: string): void {
  try {
    window.sessionStorage.removeItem(key);
  } catch {
    // nothing to clear
  }
}
