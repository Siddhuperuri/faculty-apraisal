# Prompt for Codex: make the FAMS interface visually outstanding (frontend only)

Copy everything below the line into Codex, with the repository open at `C:\Projects_AI\faculty-apraisal`.

---

## Your task

You are a senior product designer and front-end engineer. Transform the **look, feel and usability** of the Faculty Appraisal
Management System for Sri Vasavi Engineering College (Autonomous) so it feels like a premium, modern, trustworthy institutional
product, something a college principal would be proud to show. **Change only the presentation layer.** Do not change what the
software does.

### Hard boundaries (do not cross)

1. **Only edit files under `frontend/`** (and, if needed, add static assets under `frontend/public/`). Do **not** touch
   `backend/`, `docs/`, `scripts/`, `.env*`, database migrations, or anything outside `frontend/`.
2. **Do not change behaviour or data contracts.** Keep `src/lib/api.ts`, `src/lib/types.ts`, `src/lib/validate.ts`,
   `src/lib/hierarchy.ts`, `src/lib/meta.ts`, `src/lib/formStructure.ts`, every URL/route, every request and response shape,
   every `fetch`, and all autosave / validation / permission logic exactly as they are. You may restructure JSX and CSS freely; you
   may not alter what the screens send to or expect from the server.
3. **Keep every existing test green** and add none that depend on pixel values. Before you finish, from `frontend/` run
   `npm run typecheck`, `npm run lint`, `npm test` and `npm run build`; all four must pass with no new warnings.
4. **No external network use at runtime.** The product runs only inside the college network with no internet. No CDN links, no
   hosted fonts loaded at runtime, no analytics, no remote images or icon fonts. Fonts must keep using `next/font` (self-hosted
   at build time). Icons must be inline SVG or a small bundled library. Any new npm dependency must be small, well maintained,
   bundled at build time, and justified in your summary (prefer none; plain CSS, Tailwind and inline SVG are enough).
5. **Read the framework docs first.** This project uses a very recent Next.js (16) and React 19 with Tailwind 4. Its APIs differ
   from older versions. Before writing code, read the relevant guides in `frontend/node_modules/next/dist/docs/` and
   `frontend/AGENTS.md`. Do not assume older conventions.
6. **Accessibility is not optional.** WCAG 2.2 AA contrast, visible `:focus-visible` rings, full keyboard operation, semantic
   landmarks and headings, `aria-*` already present must be preserved, and meaning must never depend on colour alone (status
   stamps, check results and stage bars already include words; keep that). Respect `prefers-reduced-motion`.
7. **Never print, hard-code or commit any password or secret.**

### What the product is (so your design fits it)

A digital version of the college's printed six-page *Faculty Self-Appraisal & Assessment Report*. Faculty fill it in section by
section with autosave; it is reviewed in turn by the **Head of the Department → Dean → Vice Principal → Principal**; an
**Administrator** manages accounts, departments, academic years, scoring policy and sees an audit trail. Six roles, six
consoles. Audience: faculty (many, not all comfortable with software), senior academics, administrators. Used on laptops and
phones. It is a **serious institutional tool**: calm, authoritative, precise. Not playful, not a generic SaaS dashboard.

### Current design language (evolve it, do not throw it away)

Called "the college register". Tokens live in `frontend/src/app/globals.css` (`@theme`): warm ivory paper canvas with a faint
grain, deep navy ink (`--color-navy`), the college blue from the official form (`--color-brand`), a thin ochre accent taken from
the seal's gold (`--color-ochre`), editorial serif **Newsreader** for titles/numerals and **Hanken Grotesk** for data, status shown
as a rubber stamp, an eleven-mark page ruler, outlined section numerals, a letterhead header with the college seal
(`frontend/public/svec-logo.png`). Reusable primitives are in `src/components/ui/primitives.tsx`, `Dialog.tsx`, `FieldInput.tsx`
and `src/components/console/parts.tsx`. Strengthen this identity; make it richer, more confident and more refined.

### Where to make it impressive (priority order)

1. **Login page** (`src/app/login/page.tsx`): a memorable first impression. Rich seal panel (considered composition of the
   crest, college name, accreditation lines, subtle depth/texture), elegant form, thoughtful error and loading states, perfect on
   a phone.
2. **App shell and navigation** (`src/components/AppShell.tsx`, the tab bars in `app/(app)/admin/layout.tsx` and
   `components/console/parts.tsx`): a more polished header, clear current-location cues, a refined user menu (Account, Sign out),
   tasteful sticky behaviour, good mobile navigation.
3. **The six consoles**: Faculty (`app/(app)/faculty/page.tsx`), HoD (`hod/page.tsx`), Dean / Vice Principal / Principal
   (`components/console/LevelConsolePage.tsx`), Administrator (`app/(app)/admin/page.tsx`). Make the numbers feel important:
   better stat tiles, richer stage bars and charts built with plain SVG/CSS, a clearer "what needs my attention now" hero,
   beautiful empty and loading states (skeletons that match the final layout), elegant data tables (sticky headers, row hover,
   density that breathes, responsive card layout on narrow screens instead of horizontal scrolling where possible).
4. **The appraisal workspace** (`app/(app)/appraisals/[id]/*`, `components/appraisal/*`): the heart of the product. A superb
   section navigator (progress, completion cues, current section), calm form layouts, clear grouping and spacing, refined
   tables-of-records with add/edit/duplicate/delete, a delightful autosave indicator, an elegant score sheet with a live total, a
   clear submit panel, a clear review panel, a handsome review-history timeline, a polished documents panel (drag-and-drop zone
   look, upload progress, file-type icons).
5. **Administration** (`app/(app)/admin/*`, `components/admin/*`): accounts table, add/edit dialogs, the one-time password
   dialog, set-up page (departments, years, scoring-policy matrix and editor), audit trail.
6. **Account page** (`app/(app)/account/page.tsx`) and the forced password change: make the password checklist feel reassuring.
7. **Cross-cutting polish**: consistent spacing scale, type scale and radii; refined shadows and borders; considered
   micro-interactions (hover, press, focus, dialog open/close, list item entrance, success confirmation) that are subtle and
   fast; consistent iconography; toasts or inline confirmations in place of abrupt text swaps; helpful microcopy (keep meaning,
   improve tone); print-friendly styles for the read-only appraisal views where sensible.

### UX improvements to include (without changing behaviour)

- Make the primary action on every screen obvious; reduce visual noise; better hierarchy and scannability.
- Clearer feedback for every state: loading, saving, saved, error with a retry, success, empty, disabled-with-reason.
- Better form ergonomics: label/hint/error placement, input sizing, inline validation presentation (the existing validation
  logic stays), sensible tab order, large touch targets (min 44px) on mobile.
- Responsive from 360px to 1920px; test at 360, 768, 1280 and 1920 widths. No horizontal page scroll; tables degrade gracefully.
- Fast: no layout shift, no heavy animation libraries, keep bundle size sensible, use `next/image` for images.

### Constraints on look

Keep it **institutional and elegant**: restrained palette (navy, college blue, ochre accent, ivory), generous whitespace,
confident typography, crisp 1px rules and soft layered shadows, rounded corners used consistently. Light theme only (do not add a
dark mode). Avoid gimmicks, stock illustration, emoji, gradients that look like a template, and anything that would look out of
place on a college letterhead. Distinctive details welcome: seal-inspired motifs, a subtle watermark of the crest, ruled-ledger
lines, stamp-like badges, serif numerals.

### How to work

1. Read `frontend/AGENTS.md`, `frontend/src/app/globals.css`, the primitives and the screens listed above. Skim
   `README.md` and `docs/workflow.md` for context only (do not edit docs).
2. Propose, in a short note at the top of your final answer, the design system you are applying (tokens, scale, components).
3. Implement incrementally: tokens and primitives first, then shell, then each screen. Prefer improving shared components so every
   screen benefits.
4. Run the app and look at it. Start everything with
   `powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1`, open http://localhost:3000, and sign in with the demo
   accounts (`faculty@dev.local`, `hod@dev.local`, `dean@dev.local`, `vp@dev.local`, `principal@dev.local`, `admin@dev.local`;
   the shared password is the value of `FAMS_DEV_SEED_PASSWORD` in the git-ignored `.env`; read it from the file, never print or
   copy it into any file). Review each console and the appraisal workspace at the four widths above.
5. Finish by running `npm run typecheck && npm run lint && npm test && npm run build` in `frontend/` and fixing anything that
   fails.

### Definition of done

- Every screen above is visibly upgraded and consistent with one design system.
- Nothing outside `frontend/` changed; no API, type, route, validation or permission logic changed.
- typecheck, lint, tests and production build all pass.
- No new external runtime requests (check the built output for URLs), no secrets in the repo.
- Your final message lists: the design system you applied, every file you changed grouped by area, any new dependency with the
  reason, anything you deliberately left alone, and screenshots or a description of each screen at mobile and desktop widths.
