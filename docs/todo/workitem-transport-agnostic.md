Status: future / design

# WorkItem transport-agnostic JSON envelope (no AMQP headers)

## Goal
Make WorkItem fully transport-agnostic by serializing the entire WorkItem into a
canonical JSON envelope. AMQP headers must not carry WorkItem data. This enables
runtime choice of transport (Rabbit/SQS/Kafka) and non-AMQP pipelines such as
NinjaBee multi-worker.

## Constraints
- Breaking change is acceptable; no backward compatibility or fallback chains.
- Single source of truth for the WorkItem envelope (docs/spec + shared codec).
- Transport adapters must not embed WorkItem data in transport headers.

## Proposed envelope (v1)
Top-level, no nesting:
- `version`: `"1"`
- `headers`: work item headers (global)
- `messageId`: message identifier (top-level, not duplicated in headers)
- `contentType`: content type (top-level, not duplicated in headers)
- `steps`: always present (min 1), each item has:
  - `index`: step index
  - `payload`: string (text or base64 when binary)
  - `payloadEncoding`: `utf-8 | base64` (per-step)
  - `headers`: step headers (per-step)
- `observability`: always present (traceId, hops, swarmId)

Notes:
- `steps.length` is the total step count; no `step.total`.
- The current payload is defined by the last step (`steps[-1]`); the on-wire envelope does not duplicate it.
- Step headers must include tracking keys: `ph.step.service`, `ph.step.instance` (for every step).
- Step 0 must be an explicit, meaningful step; do not auto-seed steps from builder defaults.
- Runtime always sets `ph.step.service` / `ph.step.instance` for each new step (overwrites any existing values).
- `x-ph-service` is deprecated and must not appear in WorkItem headers; enforce via tests.
- Step payload may be empty; runtime still stamps minimal tracking headers.

## Plan
1) Update/approve all relevant contracts first (protected area).
   - WorkItem envelope spec + any downstream contract references.
2) Define and approve the WorkItem JSON contract (protected area).
   - Add spec and document it in docs/ARCHITECTURE.md / docs/spec.
3) Implement a shared WorkItem JSON codec in worker-sdk.
   - Serialize/deserialize WorkItem only via the codec.
4) Update transport adapters (Rabbit first) to use the codec exclusively.
   - Ignore transport headers for WorkItem data.
5) Update runtime/services/tests to rely on WorkItem JSON only.
   - Remove any header fallbacks or transport-specific assumptions.
6) Update docs, e2e tests, and scenario tooling to reflect the new envelope.
7) SDK refactor to align WorkItem semantics with the envelope.
   - Introduce canonical DTO/envelope classes in `common/worker-sdk` for on-wire format.
   - `WorkItem.Builder.build()` no longer auto-seeds step 0; first step must be explicit.
   - Separate global headers from step headers; do not merge step headers into WorkItem headers.
   - Runtime always stamps `ph.step.service` / `ph.step.instance` when adding a new step.
   - Remove `x-ph-service` from workers and stop using it in SDK/runtime.
8) Verify all serialization flows use the shared DTO/codec.
   - Audit for any ad-hoc envelope builders/parsers; remove or route through the codec.

## Tracking checklist
- [x] Runtime adds `ph.step.service` + `ph.step.instance` to every step header.
- [x] WorkItem builder no longer auto-seeds step 0; first step must be explicit.
- [x] Worker implementations stop writing `x-ph-service` into WorkItem headers.
- [x] E2E verifies tracking headers per step + no `x-ph-service` in steps.
- [x] E2E verifies `x-ph-service` is absent from message headers.

## Decisions locked
- Step payloads support binary data via `payloadEncoding=base64`.
- No top-level `payload` / `payloadEncoding` fields in the on-wire envelope.
- Observability always embedded as its own object (no copies in step headers).
- Steps always present (min 1).
- Envelope version is `"1"`.
- `headers` is the top-level work item headers name (no `workHeaders` nesting).
- `messageId` / `contentType` are top-level only (no duplication in headers).

## Risks
- Larger payloads (headers + steps now in JSON).
- Any existing tooling that expected AMQP headers will break.
- Control-plane / UI must be updated to read the new envelope.

## Related (UI V2): Debug taps auto-close
Debug taps are used to inspect **full WorkItem envelopes** in UI V2 without modifying worker code.
They become more sensitive once WorkItem is fully transport-agnostic (payloads move out of AMQP headers).

Implementation note:
- UI must treat tap payloads as sensitive (PII/secrets) and **auto-close** taps.
- Orchestrator must have a server-side fail-safe cleanup (in case the browser closes).

Tracking checklist:
- [ ] UI V2: tap panel closes taps on modal close / route change / timeout (createdAt + ttlSeconds).
- [ ] Orchestrator: scheduled + on-read cleanup of expired taps; return 404 for expired tapId.

## Related (UI V2): Debug tap viewer (separate tab)
The tap modal is useful for quick checks, but it is not a great place to inspect high-volume traffic.
We need a dedicated debug view that can be opened in a separate browser tab and focuses on **WorkItem** inspection.

Assumptions:
- Tap payloads are always **WorkItem envelopes** (no arbitrary strings).
- The viewer is best-effort: if parsing fails, it still shows raw payload.

### UX goals
- Open tap inspection in a separate tab (stable URL).
- Make it easy to copy/export payloads (raw + pretty JSON).
- Avoid losing the currently inspected message when new samples arrive.

### Proposed route
- `GET /v2/debug/taps/:tapId` (UI route only; backed by Orchestrator `GET /api/debug/taps/{tapId}`)

### Viewer layout (initial)
- **Left**: samples list (timestamp, size, quick filter/search).
- **Center**: parsed WorkItem view:
  - top-level headers (global), observability, ids
  - step timeline (`steps[]`), each step shows:
    - step headers (including `ph.step.service`, `ph.step.instance`)
    - payload (pretty JSON when possible)
- **Right**: raw JSON (monospace) + copy/download actions.

### Live update controls
- **Drain**: reads up to `maxItems` messages from the tap queue (consumes them).
- **Auto refresh**: periodically drains (e.g. every ~1.5s).
- **Pause** (UI only): stop auto-refresh and stop replacing the selected/inspected sample.
- **Pin sample** (UI only): keep a specific sample visible even while new samples arrive.

### Implementation plan (UI V2)
- [ ] Add `DebugTapViewerPage` route under `/v2/debug/taps/:tapId`.
- [ ] Add “Open in debug tab” button in the tap modal (opens the viewer route for the current `tapId`).
- [ ] Add explicit tooltips/help text for `Drain` vs `Auto refresh` vs `Pause` (avoid ambiguity).
- [ ] Implement sample selection + **Pin** semantics:
  - selected sample stays visible until user changes selection
  - when pinned, auto-refresh may append new samples but must not change the selection
- [ ] Implement **Pause** semantics:
  - stop auto-refresh timer
  - keep the currently selected/pinned sample stable
- [ ] Implement “Copy raw / Copy pretty” per sample and “Download snapshot” (JSON file) for the current tap state.
- [ ] Add best-effort WorkItem parsing helpers (WorkItem-only):
  - parse JSON payload into the WorkItem DTO/envelope shape used by the SDK
  - render steps as a timeline/accordion
  - fallback to raw when parsing fails
