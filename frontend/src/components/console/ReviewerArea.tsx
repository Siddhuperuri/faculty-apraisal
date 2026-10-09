"use client";

import { useAuth } from "@/components/auth/AuthProvider";
import { ReviewerTabs } from "@/components/console/parts";
import { Alert } from "@/components/ui/primitives";
import { LEVELS, type ReviewerRole } from "@/lib/hierarchy";

/**
 * The area of one level of the chain (Head of the Department, Principal or Director Technical): only that role gets in, and it comes
 * with the Console / Review queue tabs. The server enforces the same rule on every call.
 */
export function ReviewerArea({ role, children }: { role: ReviewerRole; children: React.ReactNode }) {
  const { user } = useAuth();
  const level = LEVELS[role];
  if (user?.role !== role) {
    return (
      <Alert tone="error" title={`${level.title} only`}>
        This console is for the {level.title}. If you need access, please contact an administrator.
      </Alert>
    );
  }
  return (
    <div className="space-y-6">
      <ReviewerTabs role={role} />
      {children}
    </div>
  );
}
