# Control-Plane Status Metrics Cleanup (status-delta / status-full)

Status: in progress  
Scope: control-plane only (workers + swarm-controller + orchestrator projections + UI-v2 consumption)

## Goal

Make `event.metric.status-*` messages:

- contract-compliant with `docs/spec/asyncapi.yaml` and `docs/spec/control-events.schema.json`
- “delta-minimal” (fast, frequent) vs “full-snapshot” (heavy, occasional) by design
- safe to consume at scale (UI-v2 should rely on Swarm Controller aggregates, not per-worker fan-out)

## Non-goals (separate initiatives)

- “Plan artifact on disk + ref (path/hash)” instead of sending plan JSON over AMQP
- Work-plane message contracts (later; CP first)
- Full “Result Pack / download test results” packaging

## Contract decisions (SSOT)

- `status-full`:
  - MUST include `data.config`, `data.io`, `data.ioState`, `data.startedAt`, `data.enabled`, `data.tps`
  - heavy snapshot; emitted on startup and on `signal.status-request` (and optionally periodically)
- `status-delta`:
  - MUST include `data.ioState`, `data.enabled`, `data.tps`
  - MUST NOT resend full snapshots (`data.config`, `data.io`, `data.startedAt`)
- Workers MUST NOT emit any `workers[]` swarm view.
- Swarm Controller is the canonical source for “swarm aggregate” views:
  - `status-delta` carries a small aggregate in `data.context`
  - `status-full` carries a full aggregate snapshot (including a per-worker list) in `data.context`

References:
- `docs/spec/asyncapi.yaml`
- `docs/spec/control-events.schema.json`
- `docs/rules/control-plane-rules.md`
- `docs/todo/control-plane-contract-enforcement.md`
- `docs/todo/control-plane-envelopes-refactor.md`

## Task tracking

### 0) Rebuild handshake (UI v1 / Orchestrator)

- [x] Add Orchestrator admin endpoints:
  - [x] `POST /api/control-plane/refresh` (non-destructive): broadcast `signal.status-request` (no cache wipe)
  - [x] `POST /api/control-plane/reset` (destructive): wipe Orchestrator projections/cache, then broadcast `signal.status-request`
- [x] UI v1: if Orchestrator is “incomplete” (missing `status-full` snapshot fields), call `POST /api/control-plane/refresh` once (throttled)

### 1) Worker SDK (per-worker status)

- [x] Remove any swarm-level fields (no `workers[]`) from worker `status-delta`
- [x] Ensure worker `status-delta` always includes `enabled/tps/ioState` (default `unknown` where needed)
- [x] Ensure worker `status-full` always includes `config/io/ioState/startedAt/enabled/tps`
- [x] Add/adjust unit tests to validate worker status envelopes against schema

### 2) Swarm Controller status aggregates

- [x] Make SC `status-delta` an aggregate-only payload in `data.context` (no per-worker list)
- [x] Make SC `status-full` a full aggregate snapshot in `data.context` including `context.workers[]`
- [x] Include scenario progress consistently in SC status (current/next step, runs)
- [x] Include journal `runId` in SC status (source: `pockethive.journal.run-id`, emit under `data.context.journal.runId`)
- [x] Add/adjust tests validating SC status envelopes against schema + “no heavy fields in delta”

### 3) Orchestrator projections / registry

- [ ] Ensure Orchestrator registry/state projections rely on SC aggregates (not per-worker status)
- [ ] Verify “refresh/rebuild from status-full” flows work with the new status shapes
- [ ] Add tests covering rebuild behavior from SC `status-full` (happy-path + missing workers)

### 4) UI-v2 consumption (no per-worker fan-out)

- [ ] Implement STOMP subscription filters in `ui-v2` to consume only:
  - [ ] `event.metric.status-delta.<swarmId>.swarm-controller.*`
  - [ ] `event.alert.alert.#`
  - [ ] `event.outcome.#`
- [ ] Implement worker list rendering from SC `status-full` snapshot (`data.context.workers[]`)
- [ ] Implement on-demand detail view behavior (optional): request SC `status-full` on entering swarm view

### 5) Contract enforcement

- [x] Add schema-driven tests that validate generated status payloads against `docs/spec/control-events.schema.json`
- [ ] Add E2E capture audit (blocking in CI): capture `ph.control` traffic during E2E and validate all payloads against schema
- [ ] Add semantic guards in tests (schema cannot express these well):
  - [ ] no heavy fields in `status-delta`
  - [ ] no `workers[]` in worker status payloads

### 6) Manual verification checklist

- [ ] Start a swarm; verify SC emits `status-delta` watermark and `status-full` on request
- [ ] Inject broken control message; verify it is rejected without requeue (no storm)
- [ ] UI-v2 open with multiple instances; verify no per-worker subscription fan-out and stable behavior
