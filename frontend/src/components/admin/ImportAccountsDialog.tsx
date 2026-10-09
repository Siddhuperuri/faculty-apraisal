"use client";

import { useRef, useState } from "react";
import { Alert, Button } from "@/components/ui/primitives";
import { Dialog } from "@/components/ui/Dialog";
import { ApiError, postFile } from "@/lib/api";
import { ROLE_LABEL } from "@/lib/labels";
import type { AdminReference, ImportResult, Role } from "@/lib/types";

/** The columns of the file, in order, as the server reads them. */
const COLUMNS = ["Name", "College e-mail address", "Role", "Employee ID", "Contact number", "Department", "Designation (cadre)"];
const MAX_BYTES = 1024 * 1024;

/** A header row alone: filling it in is the administrator's job, and a stray example row would become a real account. */
function downloadTemplate() {
  const blob = new Blob(["﻿" + COLUMNS.join(",") + "\r\n"], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "accounts-template.csv";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

/**
 * Create many accounts at once from a CSV file. The server checks every row and creates the accounts only if all of
 * them are right, so a file that is part right creates nothing and this dialog lists the rows to correct.
 */
export function ImportAccountsDialog({
  open,
  reference,
  onClose,
  onImported,
}: {
  open: boolean;
  reference: AdminReference;
  onClose: () => void;
  /** Called once accounts have been created, so the list can be refreshed. */
  onImported: () => void;
}) {
  return (
    <Dialog open={open} onClose={onClose} title="Add accounts from a file" wide>
      <Body reference={reference} onClose={onClose} onImported={onImported} />
    </Dialog>
  );
}

function Body({ reference, onClose, onImported }: { reference: AdminReference; onClose: () => void; onImported: () => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [fileProblem, setFileProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const choose = (f: File | null) => {
    setResult(null);
    setMessage(null);
    setFile(null);
    setFileProblem(null);
    if (!f) return;
    if (f.size > MAX_BYTES) setFileProblem("That file is larger than 1 MB. Split it into smaller files.");
    else if (f.size === 0) setFileProblem("That file is empty.");
    else if (!/\.(csv|txt)$/i.test(f.name) && f.type !== "text/csv") setFileProblem("Choose a CSV file (a spreadsheet saved as \"CSV\").");
    else setFile(f);
  };

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    setMessage(null);
    setResult(null);
    try {
      const r = await postFile<ImportResult>("/api/admin/users/import", file, "text/csv");
      setResult(r);
      if (r.created > 0) onImported();
    } catch (err) {
      setMessage(err instanceof ApiError ? err.message : "Could not read the file. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  if (result && result.created > 0) return <Done result={result} onClose={onClose} />;

  const departments = reference.departments.filter((d) => d.active).map((d) => d.code).join(", ");
  const designations = reference.cadres.filter((c) => c.active).map((c) => c.name).join(", ");

  return (
    <div className="space-y-5">
      <p className="text-sm">
        Upload a CSV file (a spreadsheet saved as <span className="font-semibold">CSV</span>) with one row for each person. Every account starts with the
        standard password, and each person must choose their own when they first sign in.
      </p>

      <div className="space-y-2 rounded-lg border border-line bg-canvas/60 p-4 text-sm">
        <p className="font-semibold">The first row names the columns:</p>
        <p className="font-mono text-[13px] leading-relaxed text-navy">{COLUMNS.join(" · ")}</p>
        <ul className="list-disc space-y-1 pl-5 text-muted">
          <li>
            <span className="font-semibold text-ink">Role:</span> Faculty, HoD, Principal, Director Technical or Administrator.
          </li>
          <li>
            <span className="font-semibold text-ink">Faculty</span> rows need an employee ID, one department and a designation. Department can be the code ({departments}) or the name.
          </li>
          <li>
            <span className="font-semibold text-ink">HoD</span> rows need a department; for more than one, separate them with a semicolon (CSE; ECE).
          </li>
          <li>
            <span className="font-semibold text-ink">Designation:</span> {designations}.
          </li>
          <li>Principal, Director Technical and Administrator rows need only a name, an e-mail address and the role. Contact number is always optional.</li>
        </ul>
        <div className="pt-1">
          <Button type="button" variant="secondary" size="sm" onClick={downloadTemplate}>
            Download an empty file with these columns
          </Button>
        </div>
      </div>

      {message && <Alert tone="error" title="The file could not be used">{message}</Alert>}
      {result && result.errors.length > 0 && <Problems result={result} />}

      <div>
        <label htmlFor="accounts-file" className="mb-1 block text-[13px] font-semibold tracking-wide">CSV file</label>
        <input
          ref={input}
          id="accounts-file"
          type="file"
          accept=".csv,text/csv"
          onChange={(e) => choose(e.target.files?.[0] ?? null)}
          className="block w-full text-sm file:mr-3 file:cursor-pointer file:rounded-md file:border file:border-line-strong file:bg-surface file:px-3 file:py-2 file:text-sm file:font-semibold file:text-ink hover:file:bg-brand-soft"
        />
        {fileProblem && <p className="mt-1 text-sm font-medium text-bad">{fileProblem}</p>}
        {file && <p className="mt-1 text-xs text-muted">{file.name} · {(file.size / 1024).toFixed(1)} KB</p>}
      </div>

      <div className="flex justify-end gap-2 border-t border-line pt-4">
        <Button type="button" variant="secondary" onClick={onClose}>
          Cancel
        </Button>
        <Button type="button" loading={busy} disabled={!file} onClick={() => void upload()}>
          {result && result.errors.length > 0 ? "Check the corrected file" : "Create the accounts"}
        </Button>
      </div>
    </div>
  );
}

function Problems({ result }: { result: ImportResult }) {
  return (
    <div role="alert" className="space-y-2 rounded-lg border border-bad/30 border-l-4 border-l-bad bg-bad-soft px-4 py-3.5 text-sm">
      <p className="font-semibold">
        Nothing was created. {result.moreErrors ? `The first ${result.errors.length} problems are` : `${result.errors.length} ${result.errors.length === 1 ? "problem is" : "problems are"}`} listed below.
      </p>
      <p className="text-ink/90">Correct them in your file and upload it again. The line numbers are the rows of the file, counting the first row as line 1.</p>
      <div className="max-h-64 overflow-y-auto rounded-md border border-line bg-surface">
        <table className="w-full border-collapse text-left text-sm">
          <caption className="sr-only">Problems found in the file</caption>
          <thead className="sticky top-0 bg-brand-soft text-navy">
            <tr>
              <th scope="col" className="px-3 py-2 text-[11px] font-bold uppercase tracking-[0.1em]">Line</th>
              <th scope="col" className="px-3 py-2 text-[11px] font-bold uppercase tracking-[0.1em]">Column</th>
              <th scope="col" className="px-3 py-2 text-[11px] font-bold uppercase tracking-[0.1em]">Problem</th>
            </tr>
          </thead>
          <tbody>
            {result.errors.map((e, i) => (
              <tr key={i} className="border-t border-line align-top">
                <td className="whitespace-nowrap px-3 py-2 font-semibold">{e.row}</td>
                <td className="px-3 py-2">{e.column}</td>
                <td className="px-3 py-2">{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Done({ result, onClose }: { result: ImportResult; onClose: () => void }) {
  const parts = (Object.keys(result.byRole) as Role[]).map((r) => `${result.byRole[r]} ${ROLE_LABEL[r].toLowerCase()}`);
  return (
    <div className="space-y-5">
      <Alert tone="ok" title={`${result.created} ${result.created === 1 ? "account" : "accounts"} created`}>
        {parts.join(", ")}.
      </Alert>
      <p className="text-sm">
        Each person signs in with their college e-mail address and the standard password, and is asked to choose their own straight away.
      </p>
      <div className="flex justify-end border-t border-line pt-4">
        <Button type="button" onClick={onClose}>
          Done
        </Button>
      </div>
    </div>
  );
}
