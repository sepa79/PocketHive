Status: future / design

# Debug Tap Flow (UI V2)

## Goal
Expose sampled WorkItem messages in UI V2 without adding REST endpoints to
workers. Operators should be able to tap any pipeline stage (e.g. postprocessor)
and inspect full payloads for debugging.

## Constraints
- Only Orchestrator / Scenario Manager can expose REST.
- Workers communicate via AMQP only.
- Must not rely on AMQP headers to carry WorkItem data.
- Keep implementation minimal: no per-worker sampling logic.

## Decision
Use **debug tap queues** created on demand by Orchestrator and bound to existing
work exchanges/routing keys. UI V2 requests taps via Orchestrator REST; the
Orchestrator buffers samples and returns them to UI.

This is the chosen approach (Option C) because it:
- avoids worker changes,
- does not alter message flow,
- works with multi-IO by targeting existing routing keys,
- keeps payloads fully intact.

## Data flow (high level)
1) UI V2 requests a tap for `swarmId`, `role`, `direction` (in/out), optional
   port/alias.
2) Orchestrator creates a temporary AMQP queue (exclusive + auto-delete) and
   binds it to the existing work exchange using the computed routing key.
3) Orchestrator consumes N messages into a ring buffer (or similar in-memory
   cache).
4) UI V2 polls Orchestrator for samples; Orchestrator returns the buffered
   WorkItem envelopes.
5) UI V2 closes the tap; Orchestrator deletes the queue.

## Orchestrator REST endpoints (sketch)
- `POST /debug/taps` -> create a tap
  - request: `{ swarmId, role, direction, ioName?, maxItems?, ttlSeconds? }`
  - response: `{ tapId, exchange, routingKey, limits }`
- `GET /debug/taps/{tapId}` -> read samples
  - response: `{ samples: [WorkItemEnvelope...], stats }`
- `DELETE /debug/taps/{tapId}` -> close tap

## AMQP details
- Queue: `ph.debug.<swarmId>.<role>.<uuid>`
  - `exclusive=true`, `autoDelete=true`
  - `x-message-ttl` and `x-max-length` enforced
- Bindings computed via the shared routing utility (no hand-crafted keys).

## UI V2 integration
- Swarm details view: add "Debug Tap" panel.
- User selects stage (role + in/out), starts tap, and views recent samples.
- Samples show full payload and headers (no redaction yet).

## Limits & safety
- Default `maxItems=1`, `ttlSeconds=60`.
- Orchestrator buffers only the latest N messages.
- Avoids any payload mutation; full WorkItem envelope is displayed.

## Open questions
- Should taps be persisted between UI refreshes (or require re-create)?
- Do we need a global on/off guard (e.g. config flag) for production?

## Plan
1) Update architecture docs to describe debug taps and the UI V2 entry point.
2) Implement Orchestrator endpoints + tap lifecycle.
3) Implement routing-key resolution for taps (using routing utility).
4) Add UI V2 panel for creating/consuming/closing taps.
5) Add e2e coverage for tap flow (optional if time permits).

## Tracking checklist
- [x] Document debug tap API in `docs/ORCHESTRATOR-REST.md`.
- [x] Add architecture note for debug taps in `docs/ARCHITECTURE.md`.
- [x] Implement Orchestrator debug tap service + endpoints.
- [ ] Add UI V2 debug tap panel (Hive page).
- [ ] Add minimal tests (optional).
