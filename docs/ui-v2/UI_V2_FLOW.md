# UI v2 — Shell & Flow (WIP)

This document is the single place for **UI v2 navigation/layout decisions**.

## Goals

- Predictable UX on **1920×1080** (Full HD), but use extra space (4k, wide screens).
- Stable layout (no “panel unmount flicker” when navigating).
- Fully linkable screens (URL is the state; no query params for navigation).
- A clean foundation for:
  - Scenario Browser + Editor
  - Hive (swarm management)
  - Journal
  - Login / users / permissions

## Global layout (Shell)

UI v2 is an “IDE-like” shell:

- **TopBar** (fixed height, **full width**)
  - left: **Logo** (click → Home) + **Breadcrumb**
  - middle: context toolbar area (per-view actions; optional)
  - right: **Connectivity indicator** (HAL Eye) + **Help** (Monolith) + **Login/User menu**
- **Left SideNav** (flat list)
  - **icons only** + tooltip (no nested expansion in the nav)
  - switches **sections**: Home, Hive, Journal, Scenarios, Other
  - **collapsible**
    - default: collapsed on 1080p, expanded on >1080p
    - user choice persisted in browser session storage
- **Content area**
  - section-specific layouts (2 or 3 columns), but the shell remains mounted

## Routing rules

- Use **path-based routes** (no query params for navigation state).
- `Back` must always work (browser history semantics).
- Every screen must be fully linkable/shareable via its URL.

Example:
- `/v2/scenarios`
- `/v2/scenarios/:scenarioId`
- `/v2/scenarios/:scenarioId/edit`

## Sections

### Home

Purpose:
- welcome screen
- links to documentation
- MOTD (from a file / endpoint later)
- quick tiles: Scenarios / Hive / Journal / Connectivity
- login entry (also available in TopBar)

Suggested subpages:
- `/v2/health` (also accessible from HAL Eye)

### Scenarios

Two-stage flow:

1) **Browser / Viewer** (read-only)
   - left panel: scenario tree (supports subdirectories)
   - main panel: “Overview” (description, metadata, components summary)
   - optional right panel or tab: YAML preview + bundle file viewer (RO)

2) **Editor**
   - left panel: bundle files (only for the selected scenario)
   - main panel: YAML editor (SSOT)
   - tabs: Diff / Swarm / Plan / Templates
   - top toolbar actions: Save, Undo/Redo, Validate, Reload

The 2-column vs 3-column variant is a UX choice to validate in practice.

### Hive

Purpose:
- swarm list (“table”)
- swarm details
- optional graph (React Flow)
- health/debug views

Routes (URL is the state):
- `/v2/hive`
- `/v2/hive/:swarmId`

Within Hive use tabs/panels for internal navigation, not SideNav expansion.

### Journal

Purpose:
- current runs
- history with filtering/search

### Other

Purpose:
- perf calculator and other utilities

## TopBar global controls

### Connectivity indicator (HAL Eye)

Single global status icon which merges “connect” + health:

- green: connected (STOMP) and healthy
- blue blinking: connecting / retry in progress
- orange blinking: disconnected (but auto-retry continues)
- red blinking: health check indicates problems

Click → `/v2/health`:
- details (health, connection state, last error)
- manual actions (reconnect, update credentials)
- auto-retry is on by default (no need to “keep clicking”)

### Help (Monolith)

Global help entry:

- context help (short, per-screen)
- link to general docs (initially via external links; optional Markdown viewer later)

### User menu (special tools)

Diagnostics and debug tools live under the user menu (not SideNav):
- Wire Log (Buzz v2)

---

## Hive — “Swarm close-up” graphical view (plan)

Goal: add a compact, non-flickering “zoomed-in” view per swarm (similar intent to UI v1’s `TopologyView`, but scoped to one swarm and built for stability).

### Entry point & routing

- Add a button on each swarm card/row in Hive → opens a dedicated subpage (not a modal).
- Proposed route:
  - `/v2/hive/:swarmId/view` (or `/v2/hive/:swarmId/topology` if we want it explicit)
- Breadcrumb must reflect the subpage and `Back` must work.

### What UI v1 Hive had (features to carry over)

From `ui/src/pages/hive/*`:
- Graph view (React Flow) with stable node ids:
  - per-swarm filter mode + overview mode
  - queue depth influences edge color/width
  - “Reset View” (fit) and a legend
  - node drag with persisted positions (prevents constant re-layout jitter)
- Swarm grouping + health signal per swarm row (heartbeat-derived).
- Selection loop: click node → show details panel; click list item → highlight in graph.
- Queue table in component details (depth/consumers/health).
- Extra nodes/edges for SUT (HTTP roles) and “guard” diagnostics (where available).

### Data sources (SSOT / contracts first)

Use existing SSOT docs and schemas, do not handcraft parallel parsers:
- Scenario graph (authoring-time SSOT): `docs/scenarios/SCENARIO_CONTRACT.md` (`template.bees[]` + `topology.edges[]`).
- Runtime wiring + metrics:
  - Control-plane envelopes (`status-full` / `status-delta`) decoded from `docs/spec/control-events.schema.json`.
  - Key fields needed for the view:
    - `data.io.work.queueStats[queue] = { depth, consumers, oldestAgeSec? }`
    - `data.io.queues.{in,out,routes}` (for quick IO overview)
    - `data.ioState.work` (input/output health)
    - `data.tps`, `data.startedAt`
- Swarm metadata & selection:
  - Orchestrator REST (existing Hive list/details) for swarm ids, templateId, sut id, etc.

### UX spec (compact, stable, “no flicker”)

Layout proposal for `/v2/hive/:swarmId/view`:
- Left column: worker cards (one per bee role/instance)
  - show: role + instance, enabled, TPS, IO state (in/out), “seen age”, and top queues (depth/consumers)
  - cards are clickable to select/highlight in the graph
- Main area: graph canvas (React Flow)
  - nodes: bees (from scenario template) + optional SUT node
  - edges: from scenario `topology.edges` (logical), styled using runtime queue stats when available
  - edge label: resolved queue (or logical `port` pair) + depth/consumers (tooltip for details)
- Top controls: Fit/Reset, Auto-layout (toggle), Freeze layout (toggle), Labels (toggle)

“No flicker” rules (hard requirements):
- Never blank/unmount the canvas on refresh; keep last-known graph and show a stale indicator instead.
- Keep stable React keys for nodes/edges; never regenerate ids.
- Update nodes/edges incrementally (diff + patch), not “replace arrays from scratch” on every tick.
- Persist node positions per `swarmId` + `nodeId` (session storage) to avoid jitter.
- Throttle render updates from rapid status deltas (e.g. coalesce to 250–500ms).
- Avoid layout recomputation unless:
  - first time nodes appear, or
  - user clicks “Auto-layout”, or
  - topology definition changes (rare).

### Implementation phases (no coding yet)

1) **Route + shell**: add new subpage and a button in Hive swarm card to open it.
2) **Static graph**: render nodes/edges purely from scenario `template + topology` (no metrics).
3) **Runtime metrics overlay**:
   - subscribe to decoded `status-*` snapshots for the swarm
   - fill worker cards and edge styling from `io.work.queueStats`, `ioState`, `tps`
4) **Selection & highlighting**:
   - card ↔ node synchronization
   - highlight connected edges and show tooltips (queue stats)
5) **Anti-flicker polish**:
   - position persistence + “Freeze layout”
   - update coalescing and stale UX
6) **Tests** (minimum):
   - mapping tests: (`scenario topology` + `status-full`) → graph model (nodes, edges, metrics)
   - smoke test: route renders without unmount flicker (basic “no blank on update” assertion)
