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
- VS Code plugin + Web UI: build a shared TypeScript SDK for control-plane REST + schema validation + routing helpers so both UIs consume the same contract logic.
  - VS Code extension hosts power-user workflows (scenario editing, swarm actions, journals, Buzz).
  - Web UI maximises reuse from the plugin’s core modules and embeds Monaco for editors; where reuse is not viable, the web UI provides a simpler surface rather than duplicating advanced workflows.

## Implementation-ready details (to lock down)

### Schema endpoint spec

- Endpoint: `GET /api/control-plane/schema/control-events`
- Response body: raw `control-events.schema.json` (exact file content).
- Headers: `Content-Type: application/schema+json;version="draft/2020-12"`, `ETag`, `Cache-Control: max-age=300`.
- UI behavior:
  - On 200: store schema in memory and validate all control-plane frames.
  - On 304: keep in-memory schema (no re-parse).
  - On any failure/timeout: disable control-plane ingestion and show soft warning (no fallback chain, no persisted cache).

### State merge rules (status-full + status-delta)

- `status-full` is the authoritative snapshot per `(swarmId, role, instance)`.
- `status-delta` applies only if a prior `status-full` exists for the same scope.
  - If a delta arrives for an unknown scope, trigger a `status-full` refresh (same flow as reconnect) and ignore the delta until the snapshot lands.
- Merge rules (schema-driven):
  - Apply only the fields present in the delta payload.
  - Per `docs/ARCHITECTURE.md` and the control-events schema, deltas must omit `data.config`, `data.io`, and `data.startedAt`.
  - If a delta includes any full-only fields, treat it as a schema violation (log + ignore; no state mutation).
  - Full-only fields from the last `status-full` remain unchanged by definition.
- Gaps/reconnect:
  - On reconnect, ignore deltas until a fresh `status-full` is obtained (via REST or status-request).
  - Trigger a `status-full` refresh on reconnect for the active swarm.
- Retention: keep the last `status-full` per scope for 30 minutes after the last update, then evict (UI is ephemeral; no disk cache).

### Decoder error format (Wire Log + UI badge)

- Canonical error object:
  - `receivedAt` (RFC-3339), `source` (`stomp` | `rest`), `routingKey`, `errorCode`, `message`, `schemaPath?`, `dataPath?`, `payloadSnippet?`.
- `errorCode` values: `schema-missing`, `schema-invalid`, `decode-failed`, `schema-violation`, `routing-invalid`.
- Log invalid frames without mutating UI state; surface a soft indicator with counts by `errorCode`.

### Wire Log retention + export

- Retention: ring buffer, max 5,000 entries or 10 MB (drop oldest).
- Export: JSONL file name `wire-log-YYYYMMDD-HHMMSS.jsonl`.

### STOMP reconnect/backoff

- Exponential backoff with jitter: 1s → 2s → 4s → 8s → 16s → 30s (cap), reset after successful connect.
- UI connectivity indicator reflects `connected`, `reconnecting`, `offline` states based on STOMP + schema readiness.

### Work exchange sniffing guardrails

- Opt-in flow per swarm and component (explicit user action).
- TTL: auto-unsubscribe after 60 seconds or after the first message (whichever comes first).
- Allowed targets: only `ph.work.<swarmId>.*` exchanges; no arbitrary routing keys.

### Security/sensitivity

- Wire Log may contain sensitive payloads; only accessible via special menu (no default surface).
- No masking in v1 (explicit warning banner in the Wire Log view).

## UI-v2 architecture (hybrid, strict contract)

- [x] `ControlPlaneSchemaRegistry` loads and caches the schema from the SSOT endpoint.
- [x] `ControlPlaneDecoder` parses and validates incoming STOMP frames (schema-only; no ad-hoc parsing).
- [x] `WireLogStore` (Buzz v2) retains raw frames + parsed envelopes + validation errors and exposes JSONL export.
- [x] `ControlPlaneStateStore` applies only valid envelopes and merges `status-delta` into the latest `status-full` snapshot.
- [x] `StompGateway` is the single STOMP connection and routes every frame through the decoder before state updates.
- [ ] `RestGateway` fetches on-demand `status-full` snapshots for initial state/hydration.

## 1) Control-plane subscriptions (no per-worker fan-out)

- [x] Implement STOMP subscription filters in `ui-v2` to consume only:
  - [x] `event.metric.status-delta.<swarmId>.swarm-controller.*`
  - [x] `event.alert.{type}.#`
  - [x] `event.outcome.#`
- [ ] Render worker list from SC `status-full` snapshot (`data.context.workers[]`).
- [ ] Implement on-demand detail behavior (optional): request SC `status-full` on entering swarm view.

## 2) Topology-first join (runtime SSOT in `status-full`)

- Scenario Manager remains template-only; UI reads current topology from `status-full` snapshots.
- [ ] Extend `docs/scenarios/SCENARIO_CONTRACT.md` with:
  - `template.bees[].id`
  - `template.bees[].ports`
  - optional `template.bees[].ui`
  - `topology.edges[]` + validation rules
- [ ] Extend `Bee` (swarm-model) with `id` (or parallel field) and propagate it into runtime worker identity mapping.
- [ ] Embed the current template snapshot + `topology.edges[]` in `status-full.data.context` for UI join.
- [ ] Emit SC `status-full.data.context.bindings` (work-plane materialisation) and include a stable scenario identifier for UI join.
- [ ] Update SC `workers[]` aggregate to include `beeId` for each runtime instance (so UI can join per-node when roles repeat).
- [ ] Update UI to draw from `topology.edges[]` + node metadata from `template.bees[]`, join runtime by `beeId`.

## 3) Wire Log and debug tooling

- [x] Add special-menu entry for Wire Log as the only UI entry point.
- [x] Persist raw frames + parsed envelopes + validation errors in `WireLogStore`.
- [x] Show soft indicator when invalid messages are observed (do not block UI).
- [x] Add JSONL export of the wire log (raw + parsed + errors).
- [ ] Add raw-only work exchange subscription support (debug-only; no schema yet).

### Wire Log UI spec (v2)

**Primary view**
- Dense list/table (single-line rows), not cards.
- Columns: time, kind/type, routing key, scope (`swarmId/role/instance`), status/result, origin.
- Row click opens a modal with:
  - raw payload (Monaco JSON),
  - validation errors (if any),
  - copy-to-clipboard action.

**Filters**
- Source: `stomp` / `rest`.
- Kind: `signal` / `outcome` / `metric` / `event`, plus `type` filter.
- Routing key prefix filter.
- Scope filters: `swarmId`, `role`, `instance`.
- `Errors only` toggle (invalid + alert level=error + failed outcomes).
- CorrelationId filter (exact match).

**Status header**
- Counts: total, invalid, error, alerts, outcomes.
- Retention indicator (entries + MB).
- Actions: clear, export JSONL (optionally export filtered set).

**Visual language**
- Color chips by `kind`.
- Status badges for `data.status` (outcomes).
- Invalid rows highlighted with `errorCode`.
- CorrelationId color band for quick grouping.

**Behavior**
- Tail mode: auto-scroll when at bottom; pause on user scroll.
- Keyboard navigation (up/down, enter to expand).
- No masking in v1; show a warning banner that payloads may be sensitive.

**Implementation status**
- [x] Dense table layout with modal details (Monaco JSON).
- [x] Filters + tail mode + search.
- [x] Correlation color band + kind chips.
- [x] Export JSONL + clear.
- [ ] Keyboard navigation.

## 4) Manual verification

- [ ] UI-v2 open with multiple instances; verify no per-worker subscription fan-out and stable behavior.
