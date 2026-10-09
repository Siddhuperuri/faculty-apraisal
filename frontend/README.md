# FAMS frontend

The pages of the Faculty Appraisal Management System: Next.js 16 (App Router), React 19, Tailwind 4, TypeScript.
Set-up, the API and deployment are described in the repository's main `README.md` and `docs/`.

## Commands

| Command | What it does |
|---|---|
| `npm run dev` | Development server on http://localhost:3000 (needs the backend on port 8080) |
| `npm run check` | Everything a change must pass: type check, lint (warnings fail), unit tests, production build |
| `npm run typecheck` / `npm run lint` / `npm test` / `npm run build` | The same steps one at a time |
| `npm start` | Serves the production build; behind the reverse proxy use `npm start -- -H 127.0.0.1` |

## How it is put together

- `src/app` - routes. `(app)/` holds every signed-in page; `login/` is the only public one.
- `src/components` - the form (`appraisal/`), the reviewers' consoles (`console/`), administration (`admin/`), shared
  controls (`ui/`) and the session (`auth/AuthProvider.tsx`).
- `src/lib` - `api.ts` (the only place that talks to the backend), form structure, labels, validation hints.
- `src/proxy.ts` - sets the Content-Security-Policy for every page, with a fresh nonce per request.
- `next.config.ts` - proxies `/api/*` to the backend (`BACKEND_URL`, read at build time), sets the other security
  headers, and limits the image optimizer to the one picture the application ships.

## Rules worth knowing before changing anything

- **The server decides.** Roles, visibility and validation are enforced by the backend; what a page hides or checks is
  for convenience only. Form fields and their limits come from `GET /api/sections/meta`, not from this code.
- **The browser only talks to its own origin**, through `src/lib/api.ts`, which adds the CSRF header to every write and
  treats a 401 as the end of the session. Do not call `fetch` to `/api` from anywhere else without the same care.
- **No inline scripts and no `dangerouslySetInnerHTML`.** The Content-Security-Policy blocks the first; the second would
  be the first place in the application where text from a user could become markup.
- **Pages are rendered per request** (the root layout waits for a request so the nonce can be applied). `next build`
  therefore lists every route as dynamic; that is intended.
- This Next.js version differs from older ones in places (`proxy.ts` replaces `middleware.ts`, for example). The
  guides for the installed version are in `node_modules/next/dist/docs/`.
