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
- `payload`: string (text or base64 when binary)
- `payloadEncoding`: `utf-8 | base64`
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
- Step headers must include tracking keys: `ph.step.service`, `ph.step.instance` (for every step).
- Step 0 must be an explicit, meaningful step; do not auto-seed steps from builder defaults.
- Runtime always sets `ph.step.service` / `ph.step.instance` for each new step (overwrites any existing values).
- `x-ph-service` is deprecated and must not appear in WorkItem headers; enforce via tests.
- Step payload may be empty; runtime still stamps minimal tracking headers.

## Plan
1) Define and approve the WorkItem JSON contract (protected area).
   - Add spec and document it in docs/ARCHITECTURE.md / docs/spec.
2) Implement a shared WorkItem JSON codec in worker-sdk.
   - Serialize/deserialize WorkItem only via the codec.
3) Update transport adapters (Rabbit first) to use the codec exclusively.
   - Ignore transport headers for WorkItem data.
4) Update runtime/services/tests to rely on WorkItem JSON only.
   - Remove any header fallbacks or transport-specific assumptions.
5) Update docs, e2e tests, and scenario tooling to reflect the new envelope.
6) SDK refactor to align WorkItem semantics with the envelope.
   - Introduce canonical DTO/envelope classes in `common/worker-sdk` for on-wire format.
   - `WorkItem.Builder.build()` no longer auto-seeds step 0; first step must be explicit.
   - Separate global headers from step headers; do not merge step headers into WorkItem headers.
   - Runtime always stamps `ph.step.service` / `ph.step.instance` when adding a new step.
   - Remove `x-ph-service` from workers and stop using it in SDK/runtime.
7) Verify all serialization flows use the shared DTO/codec.
   - Audit for any ad-hoc envelope builders/parsers; remove or route through the codec.

## Tracking checklist
- [ ] Runtime adds `ph.step.service` + `ph.step.instance` to every step header.
- [ ] WorkItem builder no longer auto-seeds step 0; first step must be explicit.
- [ ] Worker implementations stop writing `x-ph-service` into WorkItem headers.
- [ ] E2E verifies tracking headers per step + no `x-ph-service` in steps.
- [ ] E2E verifies `x-ph-service` is absent from message headers.

## Decisions locked
- Binary payloads supported via `payloadEncoding=base64`.
- Observability always embedded as its own object (no copies in step headers).
- Steps always present (min 1).
- Envelope version is `"1"`.
- `headers` is the top-level work item headers name (no `workHeaders` nesting).
- `messageId` / `contentType` are top-level only (no duplication in headers).

## Risks
- Larger payloads (headers + steps now in JSON).
- Any existing tooling that expected AMQP headers will break.
- Control-plane / UI must be updated to read the new envelope.
