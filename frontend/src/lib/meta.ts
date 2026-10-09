import { get } from "./api";
import type { SectionMeta } from "./types";

let cached: Promise<Record<string, SectionMeta>> | null = null;

/** Section definitions are fetched once per page load and shared. */
export function loadMetas(): Promise<Record<string, SectionMeta>> {
  if (!cached) {
    cached = get<SectionMeta[]>("/api/sections/meta")
      .then((list) => Object.fromEntries(list.map((m) => [m.key, m])))
      .catch((e) => {
        cached = null; // allow a retry after a failure
        throw e;
      });
  }
  return cached;
}
