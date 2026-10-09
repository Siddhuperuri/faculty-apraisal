"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/components/auth/AuthProvider";
import { homeFor } from "@/lib/hierarchy";

/** Sends each role to its home screen. */
export default function Home() {
  const { user } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!user) return;
    router.replace(homeFor(user.role));
  }, [user, router]);

  return null;
}
