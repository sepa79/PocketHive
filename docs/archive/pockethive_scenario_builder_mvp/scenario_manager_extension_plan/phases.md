
# Phased Delivery

## Phase 1 — Core CRUD & Validation
- Endpoints: list/get/create/update/delete scenarios.
- JSON Schema validation for Scenario (strict fields, blocks shape).

## Phase 2 — Apply API
- `POST /apply` inline or by id.
- Returns `{ runId }` and echoes canonicalized scenario.

## Phase 3 — Runs & Events
- `/runs`, `/runs/{runId}` status endpoint (proxy or cache of Orchestrator state).
- `/runs/{runId}/events` SSE for UI tailing.

## Phase 4 — Storage & Indexing
- Persistence of scenarios (Postgres) with version history.
- Text index on name/labels; pagination, sorting.

## Phase 5 — Policies & Governance
- Ownership tags, immutable published versions, audit trail (who changed what).
