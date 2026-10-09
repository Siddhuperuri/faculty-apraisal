"use client";

import { useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import type { Status } from "@/lib/types";

/** The filename the server suggested (RFC 5987 form first), else a plain fallback. */
function fileNameFrom(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1]);
    } catch {
      /* fall through */
    }
  }
  return /filename="?([^";]+)"?/i.exec(disposition)?.[1] ?? fallback;
}

/**
 * Downloads the printed form as a PDF. It fetches first and only then saves, so a problem shows as a message
 * here instead of a broken file. Until the appraisal is approved the PDF is a watermarked draft; after approval
 * it is the official copy, identical every time.
 */
export function ReportDownload({ appraisalId, status, variant = "secondary" }: { appraisalId: number; status: Status; variant?: "primary" | "secondary" }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const official = status === "APPROVED";

  const download = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/appraisals/${appraisalId}/report.pdf`, { credentials: "same-origin" });
      if (!res.ok) {
        let message = "The report could not be created. Please try again.";
        try {
          message = ((await res.json()) as { message?: string }).message ?? message;
        } catch {
          /* keep the default */
        }
        throw new Error(message);
      }
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = fileNameFrom(res.headers.get("content-disposition"), `Appraisal-${appraisalId}.pdf`);
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch (e) {
      setError(e instanceof Error ? e.message : "The report could not be created. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-2">
      <Button variant={variant} onClick={() => void download()} loading={busy}>
        {official ? "Download official report (PDF)" : "Download draft report (PDF)"}
      </Button>
      {error && <Alert tone="error" title="Could not download">{error}</Alert>}
    </div>
  );
}
