"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, get, onUnauthorized, post } from "@/lib/api";
import type { Me } from "@/lib/types";

interface AuthContext {
  /** undefined while the first check is running, null when signed out. */
  user: Me | null | undefined;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Re-reads who is signed in (after a password change, which clears the "must change" hold). */
  refresh: () => Promise<void>;
}

const Ctx = createContext<AuthContext | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<Me | null | undefined>(undefined);

  useEffect(() => {
    let alive = true;
    get<Me>("/api/auth/me")
      .then((me) => alive && setUser(me))
      .catch((e) => {
        if (!alive) return;
        // 401 just means "not signed in"; any other failure also leaves the user at the sign-in screen.
        if (!(e instanceof ApiError) || e.status !== 401) console.error(e);
        setUser(null);
      });
    return () => {
      alive = false;
    };
  }, []);

  // A 401 from any later call means the session ended: go to sign-in.
  useEffect(() => {
    onUnauthorized(() => {
      setUser(null);
      router.replace("/login?expired=1");
    });
    return () => onUnauthorized(null);
  }, [router]);

  const login = useCallback(async (email: string, password: string) => {
    const me = await post<Me>("/api/auth/login", { email, password });
    setUser(me);
  }, []);

  const logout = useCallback(async () => {
    try {
      await post("/api/auth/logout");
    } finally {
      setUser(null);
      router.replace("/login");
    }
  }, [router]);

  const refresh = useCallback(async () => {
    setUser(await get<Me>("/api/auth/me"));
  }, []);

  const value = useMemo(() => ({ user, login, logout, refresh }), [user, login, logout, refresh]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthContext {
  const c = useContext(Ctx);
  if (!c) throw new Error("useAuth must be used inside AuthProvider");
  return c;
}
