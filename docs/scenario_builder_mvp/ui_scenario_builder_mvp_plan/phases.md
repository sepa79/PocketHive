
# Phased Delivery

## Phase 0 — Bootstrap (1–2 days)
- Create Scenario Builder micro-frontend (Module Federation remote `@ph/scenario`).
- Integrate with shell (route `/scenario/*`, shared design system, auth context).

## Phase 1 — Assets & Storage
- CRUD screens (local-only for MVP): SUTs, Datasets, Swarm Templates.
- Save/Load Scenario JSON via Scenario Manager (basic POST/GET).

## Phase 2 — Timeline Composer
- Tracks (one per swarm instance group).
- Blocks: `hold`, `ramp`, `pause` with strict fields (operation/unit/value/from/to/durationSec/atSec).
- DnD add/move/resize with snap-to-grid; non-overlap validation.

## Phase 3 — Preview & Validation
- Local dry-run math: TPS over time, cumulative ops.
- Warnings for block overlap, invalid fields, ratio bounds.

## Phase 4 — Apply & Monitor
- Export (download JSON), Save to Scenario Manager, Apply (call Orchestrator start flow).
- Run list (read-only), basic event tail (SSE).

## Phase 5 — Polish
- Keyboard shortcuts, duplicate/align, undo/redo (shallow history).
- Persistent drafts in localStorage.
