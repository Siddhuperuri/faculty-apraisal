/**
 * Thin fetch wrapper for the backend. It adds the CSRF header to state-changing requests, turns every failure
 * into an ApiError with a message that is safe to show, and reports an expired session once, centrally.
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public fieldErrors?: Record<string, string>,
    public requestId?: string,
  ) {
    super(message);
  }
}

let unauthorizedHandler: (() => void) | null = null;

/** Register what happens when any call returns 401 (session expired). */
export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const hit = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : null;
}

async function csrfToken(): Promise<string> {
  const existing = readCookie("XSRF-TOKEN");
  if (existing) return existing;
  const res = await fetch("/api/auth/csrf", { credentials: "same-origin" });
  const body = (await res.json()) as { token: string };
  return body.token;
}

type ErrorPayload = { message?: string; fieldErrors?: Record<string, string>; requestId?: string; code?: string } | null;

/** One place that decides what a failed response means, so fetch and upload behave identically. */
function failure(status: number, payload: ErrorPayload, path: string): ApiError {
  if (status === 401 && path !== "/api/auth/login" && path !== "/api/auth/me") unauthorizedHandler?.();
  const message =
    payload?.message ??
    (status === 401 ? "Your session has ended. Please sign in again." : "Something went wrong. Please try again.");
  return new ApiError(status, message, payload?.fieldErrors, payload?.requestId);
}

export async function api<T>(method: "GET" | "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") {
    headers["Content-Type"] = "application/json";
    headers["X-XSRF-TOKEN"] = await csrfToken();
  }

  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection; your entries on this page are kept.");
  }

  if (res.status === 204) return undefined as T;

  let payload: ErrorPayload = null;
  try {
    payload = await res.json();
  } catch {
    payload = null;
  }

  if (!res.ok) throw failure(res.status, payload, path);
  return payload as T;
}

/** Sends a file as the body of a POST (a CSV file of accounts), with the same CSRF header and error handling as the rest. */
export async function postFile<T>(path: string, file: Blob, contentType: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": contentType, "X-XSRF-TOKEN": await csrfToken() },
      credentials: "same-origin",
      body: file,
    });
  } catch {
    throw new ApiError(0, "Cannot reach the server. Check your connection and try again.");
  }
  let payload: ErrorPayload = null;
  try {
    payload = await res.json();
  } catch {
    payload = null;
  }
  if (!res.ok) throw failure(res.status, payload, path);
  return payload as T;
}

export const get = <T>(path: string) => api<T>("GET", path);
export const post = <T>(path: string, body?: unknown) => api<T>("POST", path, body);
export const put = <T>(path: string, body?: unknown) => api<T>("PUT", path, body);
export const del = <T = void>(path: string) => api<T>("DELETE", path);
