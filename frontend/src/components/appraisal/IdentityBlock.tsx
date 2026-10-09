"use client";

import { useAppraisal } from "./AppraisalProvider";

/**
 * The read-only top of Part A, as the form prints it: name, academic year, employee ID, department, designation
 * and e-mail. These come from the faculty record and cannot be edited on the appraisal; everything else in Part A
 * (contact number, qualification, joining dates...) is entered below.
 */
export function IdentityBlock() {
  const { appraisal } = useAppraisal();
  if (!appraisal) return null;

  const rows: [string, string][] = [
    ["Name of the Faculty", appraisal.facultyName],
    ["Academic Year", appraisal.academicYear],
    ["Employee ID", appraisal.employeeId],
    ["Designation (Cadre)", appraisal.cadre],
    ["Department / Branch", appraisal.department],
    ["E-mail ID", appraisal.email],
  ];

  return (
    <div className="space-y-2">
      <dl className="grid overflow-hidden rounded-xl border border-line bg-surface shadow-[var(--shadow-paper)] sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        {rows.map(([label, value], i) => (
          <div key={label} className={`grid grid-cols-[9.5rem_minmax(0,1fr)] border-line ${i >= 2 ? "border-t" : ""} ${i % 2 === 1 ? "sm:border-l" : ""} ${i === 1 ? "max-sm:border-t" : ""}`}>
            <dt className="bg-brand-soft/70 px-3 py-2.5 text-[11px] font-bold leading-snug tracking-[0.025em] text-navy">{label}</dt>
            <dd className="break-words px-3 py-2.5 text-sm font-medium sm:text-[15px]">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="text-xs text-muted">These details come from your faculty record. To correct them, please contact the administrator.</p>
    </div>
  );
}
