# UI (React) Guidelines — PocketHive

Scope: contributions to the PocketHive web UI (`ui-v2/`) and archived legacy UI (`archive/legacy-ui/`).

Goals:
- Keep the UI predictable on 1080p and smaller screens.
- Avoid regressions from global CSS and ad-hoc layout rules.
- Prefer small, testable units over mega-components.
- Keep YAML-as-SSOT safe: avoid data loss when editing scenarios.

## 1) Codebase reality (read this first)

- `ui-v2/` is the active UI served under `/v2/*` and should remain clean (no global CSS imports from `/css/*`).
- `archive/legacy-ui/` is the archived UI v1 source. Do not add new product functionality there.

Rule: when adding new UI functionality, implement it in `ui-v2/` unless a human explicitly asks for an archive-only legacy UI change.

## 2) Layout & responsiveness (must-follow)

- Baseline support: **1920×1080** (Full HD). Avoid layouts that only work at 4K.
- Below 1080p is best-effort only (no guarantees).
- Support for >1080p should be “native” (use available space; avoid pointless empty gutters).
- Practical rules for >1080p:
  - Do not lock entire pages to a small `max-w-*` unless the content is genuinely “reading width”.
  - Prefer responsive grids/splits that expand (`minmax(0, 1fr)`, `flex-1`, `min-w-0`) over fixed columns.
  - Add extra panels/columns only at large breakpoints (e.g. show 3rd column at `2xl`) instead of squeezing everything at 1080p.
  - Treat “empty gutters” as a smell: if the screen grows, the UI should either add density (more columns/panels) or increase readable area.
- Avoid hard-coded fixed widths unless you also provide breakpoints:
  - Prefer `max-w-*`, `min-w-0`, `overflow-*`, `flex-wrap`, and `grid` with `minmax(0, 1fr)`.
- Avoid “layout by global CSS”.
  - `ui-v2/`: keep styling local to the app; do not import legacy `/css/*`.
- Modals must not rely on ancestor layout/overflow:
  - Render modals via a portal (`document.body`) or an equivalent top-level container.
  - Use `vw/vh` caps (`max-w-[..]`, `h-[..vh]`) so modals don’t overflow on small screens.

## 3) Component structure (keep it small)

- Do not add new logic to mega-files unless unavoidable.
  - Prefer extracting to `ui-v2/src/components/...` or `ui-v2/src/pages/<area>/...`.
- Prefer “thin pages, thick components/hooks”:
  - Page: routing, data fetching, composition.
  - Components: rendering + local interactions.
  - Hooks: derived state, effects, event wiring.
- Keep props typed and explicit; avoid “bag of props” objects.

## 4) State, data fetching, and side effects

- Prefer **React Query** for server state (fetch/cache/retry).
- Prefer the existing `ui-v2` state patterns for cross-page UI state.
- Routing (`ui-v2/`):
  - Prefer **path-based routes** (`/v2/scenarios/:id/edit`) over query params (`?id=...`) for app navigation state.
  - `Back` must always work (use browser history semantics; don’t require “special” state to return to a usable screen).
  - Every screen must be fully linkable/shareable via its URL (no “empty screen unless state is present”).
- Avoid “derived state stored as state”; compute via `useMemo` when possible.
- Effects must be deterministic:
  - Clean up subscriptions/timeouts on unmount.
  - Avoid effects that depend on unstable inline functions.

## 5) Editing YAML / JSON safely (Scenario editor rules)

- YAML is SSOT. Edits must preserve unknown subtrees:
  - Never “replace whole object” when applying a partial patch unless the contract says so.
  - Prefer deep-merge semantics for config patches (and add tests when changing merge behaviour).
- When offering structured editors, always provide an escape hatch:
  - “Edit YAML fragment” should be available for advanced users and for fields not covered by capabilities.
- Avoid “invisible normalisation”:
  - If formatting will change (durations, ordering), make it explicit and/or opt-in.

## 6) Styling conventions

- Prefer Tailwind utilities for local layout/spacing.
- Use CSS Modules only when Tailwind becomes unreadable or when you need complex selectors/animations.
- Do not add new global selectors unless the change is explicitly about the active UI theme.

## 7) Accessibility & UX baseline

- Every interactive element needs:
  - Keyboard access (`button`, `input`, proper `onKeyDown` where needed).
  - Text/tooltip/`aria-label` for icon-only controls.
- Modals:
  - Close on explicit “X” and “Cancel”.
  - Avoid surprise resizing when filters/toggles change.

## 8) Tests (what to add, where)

- Prefer `vitest` + React Testing Library for component logic.
- Add tests when changing:
  - YAML merge/patch behaviour
  - parsing/normalisation logic
  - critical editor widgets (e.g. weighted choice, interceptors editor)

## 9) PR hygiene checklist (UI)

- Builds with Docker Node 20 (`ui-v2/Dockerfile`).
- No new global CSS regressions (layout doesn’t change on unrelated pages).
- Responsive smoke check: 1366×768 and 1920×1080.
- No data loss when editing scenarios/templates/plans.
