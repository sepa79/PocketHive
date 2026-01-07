# UI v2 Control-Plane Adoption Plan

Scope: UI-v2 consumption of control-plane status/outcomes and topology join after the
envelope refactor. This consolidates remaining UI items from:
- `docs/inProgress/control-plane-status-metrics-cleanup.md`
- `docs/todo/control-plane-envelopes-refactor.md`

## Architecture decisions (current)

- Schema source: load `docs/spec/control-events.schema.json` from an orchestrator-served endpoint at UI startup.
  - No fallback chain; if schema is unavailable, control-plane ingestion is disabled and the UI shows a soft warning.
- Invalid messages: log + soft indicator; do not mutate UI state.
- Work exchange logging: raw-only for now, with optional schema extensions later via scenario bundle debug schemas.
- Wire log export: JSONL (raw + parsed + validation errors per line).
- Work exchange sniffing: opt-in debug flow (component action to capture a message), raw-only for now.

## UI-v2 architecture (hybrid, strict contract)

- `ControlPlaneSchemaRegistry` loads and caches the schema from the SSOT endpoint.
- `ControlPlaneDecoder` parses and validates incoming STOMP frames (schema-only; no ad-hoc parsing).
- `WireLogStore` (Buzz v2) retains raw frames + parsed envelopes + validation errors and exposes JSONL export.
- `ControlPlaneStateStore` applies only valid envelopes and merges `status-delta` into the latest `status-full` snapshot.
- `StompGateway` is the single STOMP connection and routes every frame through the decoder before state updates.
- `RestGateway` fetches on-demand `status-full` snapshots + scenario topology for initial state/hydration.

## 1) Control-plane subscriptions (no per-worker fan-out)

- [ ] Implement STOMP subscription filters in `ui-v2` to consume only:
  - [ ] `event.metric.status-delta.<swarmId>.swarm-controller.*`
  - [ ] `event.alert.{type}.#`
  - [ ] `event.outcome.#`
- [ ] Render worker list from SC `status-full` snapshot (`data.context.workers[]`).
- [ ] Implement on-demand detail behavior (optional): request SC `status-full` on entering swarm view.

## 2) Topology-first join (scenario SSOT)

- [ ] Extend `docs/scenarios/SCENARIO_CONTRACT.md` with:
  - `template.bees[].id`
  - `template.bees[].ports`
  - optional `template.bees[].ui`
  - `topology.edges[]` + validation rules
- [ ] Extend `Bee` (swarm-model) with `id` (or parallel field) and propagate it into runtime worker identity mapping.
- [ ] Add Scenario Manager REST to fetch topology (by template id/name + revision/hash).
- [ ] Emit SC `status-full.data.context.bindings` (work-plane materialisation) and include a stable scenario identifier for UI join.
- [ ] Update SC `workers[]` aggregate to include `beeId` for each runtime instance (so UI can join per-node when roles repeat).
- [ ] Update UI to draw from `topology.edges[]` + node metadata from `template.bees[]`, join runtime by `beeId`.

## 3) Wire log and debug tooling (Buzz v2)

- [ ] Add special-menu entry for Wire Log (Buzz v2) as the only UI entry point.
- [ ] Persist raw frames + parsed envelopes + validation errors in `WireLogStore`.
- [ ] Show soft indicator when invalid messages are observed (do not block UI).
- [ ] Add JSONL export of the wire log (raw + parsed + errors).
- [ ] Add raw-only work exchange subscription support (debug-only; no schema yet).

## 4) Manual verification

- [ ] UI-v2 open with multiple instances; verify no per-worker subscription fan-out and stable behavior.
