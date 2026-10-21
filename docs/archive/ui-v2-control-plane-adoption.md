# UI v2 Control-Plane Adoption Plan

> Status: **implemented baseline / archived**.
> Remaining UI hardening is tracked in `docs/todo/ui-v2-control-plane-followups.md`.
>
> **Historical plan, not the current REST/retention contract.** The old raw-envelope
> REST wrapper, delta-seeded partial cache, 30-minute eviction and role-deduplicated
> summary proposals are superseded. Current contracts are
> [Orchestrator REST §2](../ORCHESTRATOR-REST.md#2-swarm-discovery),
> [architecture §3.5](../ARCHITECTURE.md#35-status-metrics-semantics),
> `docs/spec/swarm-lifecycle.schema.json` and `docs/spec/control-events.schema.json`.
> Historical checkboxes below do not certify current implementation or define a backlog.

Scope: UI-v2 consumption of control-plane status/outcomes and topology join after the
envelope refactor. This consolidates remaining UI items from:
- `docs/inProgress/control-plane-status-metrics-cleanup.md`
- `docs/todo/control-plane-envelopes-refactor.md`

## Review findings (2026-02-25)

- Checklist mismatch: STOMP filters are marked done, but the UI subscribes to `/exchange/ph.control/#` instead of the three explicit topic families from architecture.
- Direction drift vs architecture: this plan section says UI should read topology from `status-full`; architecture now defines `template + topology` from Scenario Manager, with `status-full` used for `workers[]`, bindings, and queue stats.
- Some unchecked items appear implemented or superseded:
  - `SCENARIO_CONTRACT` documents `template.bees[].role`, `ports`, and
    `topology.edges`; `template.bees[].id` has been removed from the contract.
  - `Bee` model no longer includes `id`; `role` is the unique scenario node key.
  - Swarm Controller `status-full` already emits `context.bindings` and runtime metadata includes `templateId` as stable scenario identifier.
  - Raw work exchange debug capture is available via Debug Tap flow (UI + Orchestrator).
- Open items still look valid:
  - unify UI hydration around Orchestrator REST snapshot baseline (`RestGateway`/snapshot source consolidation),
  - remove per-worker fan-out dependency in `SwarmViewPage`,
  - live mutation targeting by `role + instance`,
  - keyboard navigation in Wire Log.

## Historical architecture decisions

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
- **Aggregation lives in Orchestrator**:
  - Orchestrator stores the latest SC `status-full` (swarm-controller only).
  - Orchestrator applies subsequent `status-delta` updates to that cached snapshot.
  - UI should prefer the Orchestrator snapshot over client-side merge.
- `status-delta` merges into the current cache for the same scope.
  - If no `status-full` exists yet, **do not seed a partial cache**.
    `ControllerStatusService` requests a full report and waits. REST retains
    `observation: {}` until a complete baseline is admitted.
  - A later `status-full` overwrites the cache with the full snapshot and continues merging subsequent deltas.
- Merge rules (schema-driven, enforced in Orchestrator):
  - Apply only the fields present in the delta payload.
  - Per `docs/ARCHITECTURE.md` and the control-events schema, deltas must omit `data.config`, `data.io`, and `data.startedAt`.
  - If a delta includes any full-only fields, treat it as a schema violation (log + ignore; no state mutation).
  - Full-only fields from the last `status-full` remain unchanged by definition.
- Current freshness is not eviction: REST `observationStale` becomes true when
  the latest accepted full/delta receipt is older than 30 seconds. A task every
  5 seconds marks root observed axes `UNKNOWN` after 40 seconds without updates.
  Registration and cached observation/config remain; there is no 30-minute eviction.
- Scope includes Controller instance and run; a new runtime cannot inherit the
  old runtime's baseline. See the current architecture/REST contract above.

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

### Orchestrator snapshot source (UI-first)

The original wrapper proposal is superseded; no wrapper or snapshot-route alias
is a compatibility contract. `GET /api/swarms/{swarmId}` returns `SwarmStateView`;
`GET /api/swarms` returns an array of the same state views. Use the complete,
examples in [Orchestrator REST](../ORCHESTRATOR-REST.md#2-swarm-discovery).

- Orchestrator-owned intent/operation state remains separate from cached context.
- REST worker details are `observation.workers[]`; summaries are root `bees[]`.
  Only raw event-stream envelopes use `data.context.workers[]`.
- `observation` is always an object, `{}` before the first full baseline, never null.
  `observedAt` is local receipt/application time, not event time or worker last-seen.
- Hydrate from REST and show missing/stale evidence explicitly. Do not reconstruct
  a partial snapshot from deltas, authoring defaults or per-worker fan-out.
- STOMP supplies live event diagnostics/updates, not a second REST contract owner.

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
- [ ] `RestGateway` fetches Orchestrator REST **SwarmStateView** for initial state/hydration.
- [ ] UI treats Orchestrator snapshot as baseline; STOMP is optional live update only.

**Orchestrator data source (current correction to the historical plan)**
- `SwarmStateView` joins the authoritative registered intent/operation state with
  one read-only Controller observation cache; it is not just a cached envelope.
- Deltas merge only into an admitted full baseline for the same Controller/run.
- REST projects cached `data.context` as `observation`. Envelope runtime, IO,
  initialization timestamp and config are not general REST root fields.

## 1) Control-plane subscriptions (no per-worker fan-out)

- [x] Implement STOMP subscription filters in `ui-v2` (if live updates are enabled):
  - [x] `event.metric.status-delta.<swarmId>.swarm-controller.*`
  - [x] `event.alert.{type}.#`
  - [x] `event.outcome.#`
- [ ] Render worker list from Orchestrator REST `observation.workers[]`.
- [ ] Hydrate swarm detail from the Orchestrator state view on entering the view.

## 1.1) Swarm summary derivation (list-swarms)

The old summary-only proposal is superseded by complete `SwarmStateView` items.
The current field and event-to-REST tables live in Orchestrator REST §2.2.
Root intent and active operations come from their owning stores, not telemetry.
Before the first full report, the registered swarm remains visible with
`observation: {}`, `observedAt: null`, `observationStale: true` and `bees: []`.

`bees[]` projects `{role, instance, image}` from cached worker observations and
retains all instances; never deduplicate by role. Detailed config/enablement/IO
state is at `observation.workers[]`. The scenario node's unique role is only an
authoring join key, not a replacement for runtime instance identity. An ambiguous
authoring join must disable mutation, not pick the first worker.

**Runtime metadata (status-full)**
- `runtime` is an envelope field (not in `data`), required for swarm-scoped
  non-signal messages and forbidden for signals/global non-signal messages.
- Raw event `data.context.workers[].runtime` mirrors the same shape for per-worker
  infra data; the REST path is `observation.workers[].runtime`.

### Historical status-full emission timing (superseded)

This table records the old plan, including removed command names and its old
timeout assumptions. Current result/outcome ownership and verified convergence
are defined by architecture §5.3, not by this historical table.

| Command | Outcome emitted when | Status-full emitted when | Timeout |
| --- | --- | --- | --- |
| `swarm-template` | `isReadyForWork && !pendingConfigUpdates` | immediately after outcome | — |
| `swarm-plan` | immediately after outcome | immediately after outcome | — |
| `swarm-start` | `isReadyForWork && !pendingConfigUpdates` | after fresh worker `status-full` snapshots | 5s |
| `swarm-stop` | immediately after outcome | after fresh worker `status-full` snapshots | 5s |

Current worker full reports refresh aggregate config evidence. This does not
make config acknowledgement, status publication or HTTP acceptance proof of
successful start/stop; the canonical convergence conditions still apply.

### Runtime component config editing (UI v2)

Live worker/bee config editing must be available in UI v2. The initial UI
surface is the Hive swarm detail `Scenario` tab, where the user selects a bee
and opens a capability-backed config-update form.

Data sources and ownership:

- Capability field definitions come from Scenario Manager `/api/capabilities`.
- Runtime target identity comes from the Orchestrator swarm snapshot
  `observation.workers[]` entry for the selected bee. Only raw Controller
  event-stream envelopes use `data.context.workers[]`.
- Runtime mutations go through Orchestrator only:
  `POST /api/components/{role}/{instance}/config`.
- The request body includes `swarmId`, `idempotencyKey`, and `patch`; the
  Orchestrator publishes `signal.config-update.<swarmId>.<role>.<instance>`.
- Current runtime config values are authoritative only when they come from a
  worker `status-full.data.config` snapshot carried through the
  swarm-controller aggregate and projected at REST `observation.workers[].config`.
- The swarm-controller aggregate must preserve the latest public worker config
  reported by `status-full`, including an explicit empty object (`config: {}`).
  Later `status-delta` events omit config and must not clear it.
- If REST `observation.workers[].config` is absent, the UI must show an explicit
  "current config unavailable" state instead of treating scenario YAML or
  capability defaults as current runtime state.
- Public config is producer-redacted; the aggregate/REST must not expose private
  configuration. A unique role supports the authoring join only; runtime
  identity and mutations still require the selected `instance` and run.

UI behavior:

- Render fields only from capability `config[]` entries.
- A selected bee with matched config capabilities must show an explicit, visible
  runtime config edit action. Capability summary text is not a substitute for
  the edit affordance.
- If the edit action is blocked, show the concrete blocking reason next to the
  action (permission, missing runtime instance, missing role, or empty
  capability config).
- Apply capability `when` conditions against the visible form/current config.
- Build a sparse patch from fields explicitly selected or changed by the user.
- Omit unselected fields. Do not send empty patches except from a dedicated
  reset action.
- After Orchestrator accepts the update, refresh the swarm snapshot and rely on
  subsequent status/outcome evidence; do not mutate local worker config as if it
  were confirmed.

## 2) Topology-first join (authoring plus runtime projection)

- Scenario Manager remains the authoring SSOT; UI reads current template and
  topology from Scenario Manager, then joins runtime status by exact unique
  `role`. REST provides runtime entries at `observation.workers[]`; retain all
  runtime instances and reject ambiguous authoring joins rather than collapsing
  a runtime list by role.
- [ ] Extend `docs/scenarios/SCENARIO_CONTRACT.md` with:
  - `template.bees[].role` as the unique scenario node key
  - `template.bees[].ports`
  - `topology.edges[]` using endpoint `{ role, port }` + validation rules
- [x] Remove `Bee.id`; do not propagate a second authoring id into runtime
  worker identity.
- [ ] Keep template/topology authoring data in Scenario Manager; do not duplicate
  it in `status-full.data.context`.
- [ ] Emit SC `status-full.data.context.bindings` (work-plane materialisation) and include a stable scenario identifier for UI join.
- [ ] Update SC `workers[]` aggregate to include `instance` for each runtime
  worker and no separate runtime `beeId`.
- [x] Update UI to draw from `topology.edges[]` + node metadata from
  `template.bees[]`, and target runtime workers by exact `role + instance`.

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
