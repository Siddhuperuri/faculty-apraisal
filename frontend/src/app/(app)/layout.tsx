"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/AppShell";
import { useAuth } from "@/components/auth/AuthProvider";
import { Skeleton } from "@/components/ui/primitives";

/** Everything inside (app) needs a signed-in user. The server enforces this too; this just avoids a flash. */
export default function AppLayout({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (user === null) router.replace("/login");
  }, [user, router]);

  if (!user) {
    return (
      <div className="mx-auto max-w-7xl space-y-4 px-4 py-10" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-10 w-1/3" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }
  return <AppShell>{children}</AppShell>;
}
