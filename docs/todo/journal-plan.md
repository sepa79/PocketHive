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
- [x] Log all incoming from Workers (non‑status), in a way that’s easy to distinguish from orchestrator/control‑plane traffic.
- [x] Add explicit journal events when worker health transitions between “healthy” and “lost / degraded”, derived from status/metrics, without logging every status tick.
- [ ] Emit journal events for plan/step lifecycle (scheduled/started/completed/retried/failed/timeout) with reasons.
- [ ] Emit journal events for worker lifecycle and message handling (dispatch, result accepted/rejected, user vs infra errors).
- [ ] Add tests per `docs/ci/control-plane-testing.md` to validate event emission and storage for a sample swarm.

## Phase 3 — Hive-Level Journal Backend

- [ ] Add a Hive‑level journal port in the orchestrator, mirroring the Swarm journal abstraction.
- [ ] Implement the Hive journal adapter (reuse the same file‑based abstraction or a compatible sink) and integrate into orchestrator services.
- [ ] Implement orchestrator‑side projections from existing orchestrator events and signals into `JournalEvent` entries without modifying shared routing utilities or public contracts.
- [ ] Emit journal events for swarm lifecycle (created/resumed/suspended/terminated/version-changed).
- [ ] Emit journal events for routing and infra behavior (queue issues, timeouts, retries, degraded mode) without modifying shared routing utilities.
- [ ] Add tests to assert that key orchestrator flows append the expected Hive journal events.

## Phase 4 — UI Integration (Swarms Table + Journal View)

- [x] Extend the Swarms table to support row expansion with a “Journal & Debug” panel per swarm.
- [ ] Add backend REST endpoints for fetching paginated Swarm and Hive journal entries by `swarmId`/`correlationId`.
- [ ] Implement journal timeline UI (filters by severity, kind, time range; linked Swarm vs Hive tabs).
- [ ] Add quick filters like “Errors only” and “Last N minutes” and surface “current health state” derived from recent events.
- [ ] Wire journal events to deep links: from a run/step view, jump into filtered journal timeline.

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
