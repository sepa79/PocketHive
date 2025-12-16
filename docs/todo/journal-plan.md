# Journal & Swarm/Hive Debug Plan

> Status: **future / design**.  
> This plan tracks the introduction of Swarm‑ and Hive‑level journals plus related debug tooling.

Goal: provide a first‑class, queryable journal for swarms (control‑plane scope) and Hive/orchestrator (system scope), wired into the existing Swarms table UI so it is easy to understand “what happened, when, and why” for any swarm or correlation. The journal should be a projection over existing events and signals (no new on‑wire contracts initially), stored in a replay‑friendly format so recordings can later be turned into reusable scenarios.

## Phase 1 — Design & Contracts

- [x] Align journal goals and scope with `docs/ARCHITECTURE.md` and `docs/correlation-vs-idempotency.md` (conceptual alignment only for MVP; detailed doc edits follow once the feature stabilises).
- [ ] Add a short “Journal” section to `docs/ARCHITECTURE.md` describing Swarm vs Hive journal streams, explicitly as projections over existing control‑plane and orchestrator events (post‑MVP docs task).
- [x] Define a canonical `JournalEvent` schema (ids, timestamps, actor, kind, severity, summary, details, pointers) plus links to the original event type and envelope.
- [ ] Enumerate initial `eventKind` values for Swarm (config, plan, worker, message, policy) and Hive (swarm lifecycle, routing, infra).
- [x] Design a storage abstraction whose initial implementation is a per‑swarm append‑only JSONL file in the swarm’s runtime directory, with rotation/retention policy, and a clear path to swap the sink to DB/log‑store later without changing producers.
- [x] Make the journal file format replay‑friendly (append‑only, ordered JSON objects with the data needed to reconstruct inputs for a new swarm without copying transient identifiers).

## Phase 2 — Swarm-Level Journal Backend

- [x] Introduce a journal port interface in the control‑plane layer (SC) with async append APIs.
- [x] Implement a file‑backed adapter behind the port that writes per‑swarm JSONL journals into the swarm’s runtime directory.
- [x] Log all incoming control signals from Orchestrator (non‑status) as normalized `JournalEvent` entries.
- [x] Log all outgoing control messages to Swarm and Workers (not just confirmations).
- [x] Log all incoming non‑status from Workers (currently alerts), in a way that’s easy to distinguish from orchestrator/control‑plane traffic.
- [x] Add explicit journal events when worker health transitions between “healthy” and “lost / degraded”, derived from status/metrics, without logging every status tick.
- [ ] Emit journal events for plan/step lifecycle (scheduled/started/completed/retried/failed/timeout) with reasons.
- [ ] Emit journal events for worker lifecycle and message handling (dispatch, result accepted/rejected, user vs infra errors).
- [ ] Add tests per `docs/ci/control-plane-testing.md` to validate event emission and storage for a sample swarm.
  - [x] Unit coverage for journal entry shapes in swarm-controller (`SwarmSignalListenerTest`).
  - [x] Orchestrator reads `journal.ndjson` and serves it to UI (`SwarmControllerTest`).
  - [ ] Add integration coverage with RabbitMQ/Testcontainers for a real swarm flow and on-disk `journal.ndjson`.

## Phase 3.5 — Journal Storage (Postgres) + Query API (prereq for Hive journal + UI pagination)

> Goal: move the Journal from “debug artifact” to a durable, queryable system suitable for multi-day/weekend test runs, Grafana annotations, and fast drill-down (without turning logs into the entry point).

### 3.5.1 Storage model (schema + indexes)

- [x] Define an initial DB-backed `JournalEvent` storage shape (ids, timestamps, kind/type, severity, origin, scope, correlation/idempotency, JSON payloads).
- [x] Choose the table layout (pick one, no cascading defaults):
  - [x] **Option A (recommended):** single `journal_event` table with `scope = 'SWARM'|'HIVE'`.
  - [ ] Option B: separate `swarm_journal_event` + `hive_journal_event` tables.
- [x] Partition by time (daily partitions recommended) so retention is `DROP PARTITION`, not slow deletes.
- [x] Define required indexes for keyset pagination + filters:
  - [x] `(ts DESC, id DESC)` (global timeline)
  - [x] `(scope, swarm_id, ts DESC, id DESC)` (per-swarm timeline)
  - [x] `(correlation_id, ts DESC, id DESC)` (attempt drilldown)
  - [x] Optional: partial index for errors-only queries (`WHERE severity IN ('WARN','ERROR')`)
- [ ] Decide how much JSON to persist:
  - [ ] `details_jsonb` for structured details (bounded size).
  - [ ] `raw_jsonb` only if required for replay/debug; otherwise store a pointer/hash.

### 3.5.2 Writer path (performance + failure isolation)

- [x] Add DB migrations (Flyway) in `orchestrator-service` to create journal tables + indexes (no partitions yet).
- [x] Implement a Postgres journal sink adapter in orchestrator behind the journal port.
- [x] Use batched inserts (JDBC batch / `COPY`-style) and an in-process bounded buffer so journal writes never block control-plane traffic.
- [x] Define overload policy (explicit):
  - [x] Drop/compact `INFO` first under backpressure, preserve `WARN/ERROR`.
  - [x] Emit a single “journal dropped events” entry when dropping starts/stops.
- [x] Define failure mode (explicit): DB down must not prevent orchestrator startup; journaling degrades to no-op with periodic health warnings.

### 3.5.3 Retention + “pin this run forever”

- [x] Implement default retention via partition pruning (e.g., keep N days).
- [x] Add “pinned capture” support for focused tests (keep a 4h window forever):
  - [x] Introduce a `journal_capture` concept (metadata) and attach it to `(swarmId, runId)` for discovery in UI “Runs”.
  - [x] Add an archive table (no TTL) and a “pin run” operation that copies events from `journal_event` into the archive before time-based retention drops partitions.
  - [x] Support pin modes to keep storage reasonable:
    - [x] `FULL` (copy 1:1),
    - [x] `SLIM` (drop `raw`/`extra`),
    - [x] `ERRORS_ONLY` (keep `WARN/ERROR` + selected lifecycle).
  - [x] Query API: when reading a pinned run, read from archive; otherwise read from `journal_event`.
  - [x] UI: mark pinned runs, and allow browsing pinned runs even after time retention.

### 3.5.4 Query API (paginated + filters)

- [x] Add REST endpoints for keyset pagination + correlation filter:
  - [x] `GET /api/journal/hive/page?swarmId=&correlationId=&beforeTs=&beforeId=&limit=`
  - [x] `GET /api/swarms/{swarmId}/journal/page?correlationId=&beforeTs=&beforeId=&limit=`
- [x] Return stable cursors based on `(ts,id)` for paging.

### 3.5.5 Grafana integration (annotations + drilldown)

- [x] Add a Grafana datasource for Postgres Journal (provisioned in Grafana, not manual steps).
- [ ] Define canonical annotation queries (SQL) for:
  - [x] swarm lifecycle transitions
  - [x] journal backpressure signals (writer overload)
  - [ ] guard kicks / queue overfill signals
  - [ ] data exhaustion / data-path failures
  - [x] `WARN/ERROR` only overlays
- [ ] Add stable deep links from Hive UI to Grafana dashboards with pre-filled filters (`swarmId`, `correlationId`, `captureId`).

### 3.5.6 Tests + CI

- [ ] Add Testcontainers Postgres tests validating:
  - [ ] batched inserts preserve ordering guarantees used by pagination
  - [ ] filter + cursor pagination correctness
  - [ ] overload policy behavior (drops only info, not warn/error)

## Phase 3 — Hive-Level Journal Backend

> Note: Hive-level journaling is implemented as a minimal Postgres-backed projection over orchestrator REST actions + control-plane outcomes. This section tracks remaining work to make it richer (infra/routing/guards, retention/pinning, UI tab).

- [x] Add a Hive‑level journal port in the orchestrator, mirroring the Swarm journal abstraction.
- [x] Implement the Hive journal adapter (Postgres) and integrate into orchestrator services.
- [x] Implement orchestrator‑side projections from existing orchestrator actions/outcomes into `JournalEvent` entries (no contract/routing changes).
- [x] Emit journal events for swarm lifecycle (create/start/stop/remove + template/plan dispatch).
- [ ] Emit journal events for routing and infra behavior (queue issues, timeouts, retries, degraded mode) without modifying shared routing utilities.
- [ ] Add tests to assert that key orchestrator flows append the expected Hive journal events.

## Phase 4 — UI Integration (Swarms Table + Journal View)

- [x] Extend the Swarms table to support row expansion with a “Journal & Debug” panel per swarm.
- [x] Add Swarm journal REST endpoint for Hive UI (`GET /api/swarms/{swarmId}/journal`).
- [x] Add backend REST endpoints for fetching **paginated** Swarm and Hive journal entries (and filtering by `correlationId`) backed by Phase 3.5 storage.
- [x] Implement Swarm journal timeline UI (search + “Errors only” + detail expansion).
- [x] Implement Hive-level timeline UI (Orchestrator/Hive journal).
- [ ] Add quick filters like “Last N minutes” and surface “current health state” derived from recent events.
- [x] Wire a deep link from the Swarms table row expansion to the full journal page.
- [ ] Wire journal deep links from run/step views once those pages exist.

## Phase 4 — Journals UX (Entry Point + Run-Centric Navigation)

> Goal: make Journals the primary operator entry point: Hive journal is always available at the top, and all swarm journals are browsable by **runId** (swarmId is metadata, not identity).

### 4.1 Journals index (Postgres)

- [x] Add a global “swarm journal runs” endpoint (Postgres-only) to list all `(swarmId, runId)` pairs with timestamps, entry counts, and pinned status (for the Journals index view).
- [x] Build a Journals index page as a top-level UI entry point:
  - Hive journal entry always at the top (unfiltered view).
  - List all Swarm runs (runId-first display, with swarmId as secondary/tooltip).
  - Toggle: `Latest` vs `Pinned`.
  - Click a run → open the dedicated journal detail view for that run.

### 4.2 Navigation rules (operator UX)

- [x] Hive view: clicking the Orchestrator opens Hive journal with **no filters** (show everything).
- [x] Hive/Swarms views: “Journal” for a swarm opens the swarm journal for the **current run** (default/active run).
- [x] Remove/avoid sentinel filters like `swarmId=hive` in Hive journal URLs; Hive journal is a single continuous timeline.

### 4.3 Run metadata (Scenario/TestPlan/Tags)

> Goal: support operator-friendly grouping and labeling without treating `swarmId` (stack name) as identity.

- [x] Introduce a `journal_run` metadata table keyed by `(swarm_id, run_id)`:
  - `scenario_id` (primary grouping), `test_plan` (higher-level grouping), `tags` (labels), `description` (run notes).
  - Add `created_at`/`updated_at` and basic indexes (by `scenario_id`, `test_plan`, `tags`).
- [x] On swarm start, upsert `journal_run` metadata at least with `scenario_id` (from templateId when available).
- [x] Add a Postgres-only REST API to update run metadata post-factum (set/clear `test_plan`, `tags`, `description`) without writing SQL manually.
- [x] Update Journals index UI:
  - Default grouping by `scenario_id`.
  - Show `test_plan` and `tags` (when present) and include them in search.
  - Keep `swarmId` as a secondary/tooltip field (technical stack id).

## Phase 4.5 — Multi-tenant Foundations (Namespace + Actor) — planned, not implemented here

> Goal: prepare Journals and Scenarios for future hard multi-tenancy (org/BU/team/user + custom levels) without implementing auth/RBAC in this branch.

- [ ] Define a canonical `NamespacePath` concept for ownership/routing (list of segments, default levels: org/BU/team/user; supports extension/redefinition).
- [ ] Capture `actor` (who triggered actions) and `namespacePath` on:
  - swarm runs (metadata)
  - pinned captures/archives
  - relevant journal events (where appropriate)
- [ ] Ensure scenario selection and swarm launching can later be constrained by namespace without reworking the journal data model.

## Phase 5 — Debug Taps & Central Logging Hooks

- [ ] Extend postprocessor to support configurable message sampling (every N‑th, per swarm/correlation, time‑boxed “debug session”).
- [ ] Define and implement a storage pathway for captured message samples, with redaction rules and TTL.
- [ ] Add a UI “Message Tap” view reachable from the swarm’s journal panel, showing sampled input/output plus worker/model/config hash.
- [ ] Augment journal events with pointers to central logs/traces (for example `traceId` or log query URLs) rather than duplicating raw logs.
- [ ] Add a “Start debug session” control in the swarm row expansion that increases journal verbosity, enables sampling, and auto‑expires.

## Phase 6 — Docs, Guardrails & Scenarios

- [ ] Document journal semantics, event kinds, and expected usage in `docs/README.md` or a new `docs/journal.md`.
- [ ] Update `docs/USAGE.md` with a short “How to debug a swarm with Journal” workflow.
- [ ] Add or extend an E2E scenario (for example `scenario-manager-service/scenarios/e2e/local-rest-plan-demo.yaml`) that exercises journal events and the debug tap.
- [ ] Run through `docs/ai/REVIEW_CHECKLIST.md` to ensure journal changes respect module boundaries and protected areas.

## Recording & Replay (future direction)

> Journal work in this plan is intended to **enable** recording/replay, not to implement it in the initial iteration. This section captures the desired direction for later.

- [ ] Define how journal recordings map onto scenario plans so that actions performed against a swarm (for example via UI) can be captured and turned into reusable scenarios.
- [ ] Specify which parts of original events are preserved for replay (logical inputs and decisions) and which are regenerated for the new swarm (timestamps, swarmIds, correlationIds, user/session names).
- [ ] Add a conceptual mapping from journal entries to `scenario-manager-service` plans (without implementing tooling yet), so the recording format stays aligned with future scenario‑builder needs.
- [ ] Ensure journal entries always carry `correlationId` (and any idempotency token) consistently with `docs/correlation-vs-idempotency.md`, while replay semantics always use fresh identifiers for new swarms.

## Appendix — DB / Metrics / Dashboards Notes (current direction)

> These are not tasks by themselves; they capture the “why” and the likely shape of Phase 3.5+ work so we don’t re-litigate the same choices later.

### Journal as the entry point (operators)

- Journal should remain **high-signal**: lifecycle, enable/disable work, guards/backpressure, data exhaustion, major infra failures, SUT health alarms.
- Prefer **Postgres + Grafana annotations** as the default “weekend overview” workflow:
  - Grafana panels show throughput/latency/queue depth.
  - Journal entries appear as annotations and as a queryable timeline with filters.
- Logs (Loki) are **deep detail** and best-effort; journal entries should point to logs/traces, not duplicate them.

### Metrics: aggregated vs per-transaction capture

- Aggregated metrics stay in Prometheus (Pushgateway batching from PP is fine for this path).
- Per-transaction capture is for **short focused tests** (minutes–hours) and post-test analysis:
  - Avoid Influx-style “TTL/rollup is a new index+key-name” pain by using an event-store DB:
    - Candidate: **ClickHouse** with `txn_raw` (short TTL) + `txn_rollup` (long retention) and an explicit archive/pin pathway.
  - “Keep this 4h test forever” maps to **pin/archive** (store by `capture_id` / `run_id`), not global retention changes.

### Transport: keep control-plane thin

- Do not route “everything” through the control-plane queues.
- Keep control-plane messages for lifecycle/coordination; telemetry (metrics/txns) should use a separate pathway.
- If PP direct-to-Pushgateway is reliable enough, keep it. Introduce a queue only when we need:
  - centralized retry/backpressure,
  - DB credential isolation,
  - or per-transaction capture fan-in.

### Dashboarding system

- Grafana remains the default dashboarding system:
  - Datasources: Prometheus (aggregates), Postgres (Journal), optionally ClickHouse (per-txn capture), optionally Loki (logs).
  - Hive UI should deep-link into Grafana with stable filter parameters (`swarmId`, `correlationId`, `captureId`).
