"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { Alert } from "@/components/ui/primitives";

const TABS = [
  { href: "/admin", label: "Overview" },
  { href: "/admin/users", label: "Accounts" },
  { href: "/admin/accounts-review", label: "Account review" },
  { href: "/admin/setup", label: "Departments, years & scoring" },
  { href: "/admin/health", label: "System health" },
  { href: "/admin/handover", label: "Handover" },
  { href: "/admin/audit", label: "Audit trail" },
];

/** Administration. Only administrators see it; the server enforces the same rule on every call. */
export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const pathname = usePathname();

  if (user?.role !== "ADMIN") {
    return (
      <Alert tone="error" title="Administrators only">
        This area is for administrators. If you need something changed, please contact one.
      </Alert>
    );
  }

  return (
    <div className="space-y-6">
      <nav aria-label="Administration" className="section-tabs border-b border-line-strong">
        <ul className="-mb-px flex flex-wrap gap-1">
          {TABS.map((t) => {
            const current = t.href === "/admin" ? pathname === "/admin" : pathname === t.href || pathname.startsWith(t.href + "/");
            return (
              <li key={t.href}>
                <Link
                  href={t.href}
                  aria-current={current ? "page" : undefined}
                  className={`inline-block rounded-t-md border-x border-t px-4 py-2 text-sm font-semibold transition-colors ${
                    current ? "border-line-strong border-b-surface bg-surface text-navy" : "border-transparent text-muted hover:text-ink"
                  }`}
                >
                  {t.label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      {children}
    </div>
  );
}
