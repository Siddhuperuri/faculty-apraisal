"use client";

import Link from "next/link";
import { Button } from "@/components/ui/primitives";

interface Item {
  text: string;
  /** Where in the application the step is done, shown on screen only. */
  link?: { href: string; label: string };
}

const BEFORE_GO_LIVE: Item[] = [
  { text: "The site is served over HTTPS with the college's certificate, and the reverse proxy passes the real client address (FAMS_FORWARD_HEADERS=native).", },
  { text: "The server is reachable from staff networks only; the database port is closed to everything except the application server." },
  { text: "The \"dev\" profile is off, and the first-administrator variables have been removed after the first sign-in." },
  { text: "FAMS_STORAGE_DIR is on a data disk that is backed up, and FAMS_BACKUP_DIR names the folder the backup script writes to." },
  { text: "The database account used by the application has only SELECT, INSERT, UPDATE and DELETE; migrations run under a separate account." },
  { text: "The server clock is on network time (NTP)." },
  { text: "Departments, the academic year and a scoring policy for every designation are set up; every set-up check on the Overview is green.", link: { href: "/admin", label: "Overview" } },
  { text: "A Principal or Director Technical, a second administrator, and a Head of the Department for every department with faculty have accounts.", link: { href: "/admin/users", label: "Accounts" } },
  { text: "Faculty accounts are imported from the CSV file; the Account review shows no duplicates and no missing assignments.", link: { href: "/admin/accounts-review", label: "Account review" } },
  { text: "A backup has been made, is scheduled to run every night, and a copy is kept away from the server.", link: { href: "/admin/health", label: "System health" } },
  { text: "A restore has been tried on a spare machine, and the test is recorded.", link: { href: "/admin/health", label: "System health" } },
  { text: "The Handover page is filled in: who manages the server, whom to call, where the backups are.", link: { href: "/admin/handover", label: "Handover" } },
  { text: "Everyone has been told the old password, that they must choose their own at first sign-in, and has the user guide." },
];

const END_OF_YEAR: Item[] = [
  { text: "Chase the appraisals still in draft or in review; every one that should be approved has been.", link: { href: "/admin/setup", label: "Departments, years & scoring" } },
  { text: "The count of approved appraisals matches the number of official reports issued." },
  { text: "A backup has been made after the last approval, checked, and a copy stored away from the server.", link: { href: "/admin/health", label: "System health" } },
  { text: "The year's readiness checklist has been read and its warnings understood, then the year is closed.", link: { href: "/admin/setup", label: "Departments, years & scoring" } },
  { text: "The next academic year is open, with a scoring policy for every designation (copy or publish a new version).", link: { href: "/admin/setup", label: "Departments, years & scoring" } },
  { text: "Staff who have left are disabled; new joiners are added (one by one or from a CSV file).", link: { href: "/admin/accounts-review", label: "Account review" } },
  { text: "Heads of the Department, the Principal and the Director Technical are still the right people, and each department has an active Head.", link: { href: "/admin/users", label: "Accounts" } },
  { text: "The audit trail has been looked through, in particular every password reset.", link: { href: "/admin/audit", label: "Audit trail" } },
  { text: "Storage has room for the next three years of reports (the figure on System health is below the warning level).", link: { href: "/admin/health", label: "System health" } },
  { text: "A restore test has been done this year and recorded.", link: { href: "/admin/health", label: "System health" } },
  { text: "The Handover page is up to date." , link: { href: "/admin/handover", label: "Handover" } },
];

function Checklist({ title, items }: { title: string; items: Item[] }) {
  return (
    <section aria-label={title} className="space-y-3">
      <h2 className="border-b border-line-strong pb-2 font-display text-xl font-semibold text-navy">{title}</h2>
      <ol className="space-y-2.5">
        {items.map((item, i) => (
          <li key={i} className="flex items-start gap-3 text-sm leading-snug">
            <span aria-hidden className="mt-0.5 inline-block h-5 w-5 shrink-0 rounded-[3px] border-2 border-ink/70" />
            <span className="flex-1">
              {item.text}
              {item.link && <Link href={item.link.href} className="no-print ml-2 font-medium text-brand hover:underline">{item.link.label} →</Link>}
            </span>
          </li>
        ))}
      </ol>
      <div className="grid gap-6 pt-4 sm:grid-cols-3">
        {["Completed by (name)", "Signature", "Date"].map((label) => (
          <div key={label}>
            <div className="h-9 border-b border-ink/70" />
            <p className="mt-1 text-xs text-muted">{label}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

/** One page for each of the two moments the administrator signs off: before going live, and at the end of each year. */
export default function ChecklistPage() {
  return (
    <div className="print-sheet space-y-8">
      <div className="no-print flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.24em] text-brand">Administration</p>
          <h1 className="mt-1 font-display text-4xl font-medium tracking-tight text-navy">Administrator checklists</h1>
          <p className="text-sm text-muted">Print this page and tick each box as it is done. The two lists print on separate pages.</p>
        </div>
        <Button onClick={() => window.print()}>Print</Button>
      </div>

      <div className="space-y-8">
        <header className="hidden print:block">
          <p className="text-xs font-semibold uppercase tracking-[0.2em]">Sri Vasavi Engineering College (Autonomous)</p>
          <h1 className="font-display text-2xl font-semibold">Faculty Appraisal Management System: administrator checklist</h1>
        </header>
        <Checklist title="Before going live" items={BEFORE_GO_LIVE} />
        <div className="print:break-before-page">
          <Checklist title="At the end of each academic year" items={END_OF_YEAR} />
        </div>
      </div>
    </div>
  );
}
