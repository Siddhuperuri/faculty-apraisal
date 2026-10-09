/** Finds a control by element id or, failing that, by its form field name. */
function find(target: string): HTMLElement | null {
  return document.getElementById(target) ?? document.querySelector<HTMLElement>(`[name="${CSS.escape(target)}"]`);
}

/** Scrolls a control into view and puts the cursor in it. Returns false when it is not on the page (yet). */
export function focusTarget(target: string): boolean {
  const el = find(target);
  if (!el) return false;
  const calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  el.scrollIntoView({ block: "center", behavior: calm ? "auto" : "smooth" });
  el.focus({ preventScroll: true });
  return true;
}

/** Scrolls an element to the top of the view (for "show me the list below"). */
export function scrollToId(id: string): void {
  const calm = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  document.getElementById(id)?.scrollIntoView({ block: "start", behavior: calm ? "auto" : "smooth" });
}
