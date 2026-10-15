"use client";

import { useCallback, useState } from "react";
import { postDownload, saveFile } from "@/lib/api";

/**
 * Downloads the printed forms of many appraisals as one zip file, from any console. The server decides which forms the
 * reader may have and which remarks are printed for them; a form that is not theirs, or a draft, is left out and counted.
 *
 * `endpoint` is the console's own (/api/hod/appraisals/pdfs, /api/principal/..., /api/director/..., /api/admin/...).
 */
export type PdfChoice = { appraisalIds: number[] } | { facultyIds: number[] } | { all: true };

export interface PdfNotice {
  tone: "ok" | "error";
  text: string;
}

export function usePdfZip(endpoint: string, academicYearId?: number | null) {
  const [busy, setBusy] = useState<"selected" | "all" | null>(null);
  const [notice, setNotice] = useState<PdfNotice | null>(null);

  const download = useCallback(
    async (choice: PdfChoice) => {
      setBusy("all" in choice ? "all" : "selected");
      setNotice(null);
      try {
        const file = await postDownload(endpoint, { ...choice, academicYearId: academicYearId ?? undefined });
        saveFile(file.blob, file.fileName);
        const included = Number(file.headers.get("X-Pdf-Included") ?? "0");
        const skipped = Number(file.headers.get("X-Pdf-Skipped") ?? "0");
        setNotice({
          tone: "ok",
          text:
            `${file.fileName} was downloaded with ${included} form${included === 1 ? "" : "s"}.` +
            (skipped > 0 ? ` ${skipped} selected appraisal${skipped === 1 ? " was" : "s were"} left out: a draft is private, and a form appears only once it has been submitted.` : ""),
        });
      } catch (e) {
        setNotice({ tone: "error", text: (e as Error).message });
      } finally {
        setBusy(null);
      }
    },
    [endpoint, academicYearId],
  );

  return { busy, notice, download, clear: () => setNotice(null) };
}
