# `correlationId` vs `idempotencyKey`

**Goal:** make control signals safe to retry and easy to trace across services.

---

## TL;DR
- **`correlationId`** → trace **this attempt** end‑to‑end across services, logs, and spans.
- **`idempotencyKey`** → ensure the **same command** is not executed twice across **retries**.

Use **both** on every control signal.

**Where these fields live**

- Envelope definitions: `docs/spec/asyncapi.yaml` and `docs/spec/control-events.schema.json`
- Both fields are top-level envelope fields on `kind=signal` and `kind=outcome`.
- Metrics/alerts may have `correlationId`/`idempotencyKey` set to `null` unless explicitly tied to a command attempt.

---

## Why both?

### Correlation alone is not enough
- Retries typically create a **new** `correlationId`. The receiver cannot tell it is the *same* command retried and may execute it again.
- Reusing a `correlationId` for all retries hides **per‑attempt** diagnostics: you lose timing and failure vs success attribution for each attempt.
- Correlation is often scoped to a **workflow or saga** that includes multiple commands. Dedup must be **per command**, not per saga.

### Idempotency alone is not enough
- Prevents duplicate work but is weak for **tracing**: heartbeats, status deltas, and internal spans are not neatly tied to a single command attempt.
- You want to know which attempt actually succeeded and how long it took. A single long‑lived idempotency key cannot represent multiple attempts cleanly.

---

## Contract

### Client sends on each control signal
- `idempotencyKey` — **stable across retries** of the same user action.
- `correlationId` — **new per attempt** (or per span if you use distributed tracing).

### Server or Controller behavior
- Deduplicate on **(swarmId, signal, idempotencyKey)** within a retention window.
- On duplicate: perform a **no‑op** and **replay** the previous outcome, or emit an identical outcome.
- All outcomes **echo both** fields so UIs and services can stitch responses to the initiating attempt and safely ignore late duplicates.

### Delivery model
- Expect **at‑least‑once** delivery with AMQP. Do not rely on “exactly‑once”. Idempotency is the safety net.

---

## Example

**Signal** (`kind=signal`, `type=swarm-start`)
```json
{
  "timestamp": "2025-09-12T12:30:08Z",
  "version": "1",
  "kind": "signal",
  "type": "swarm-start",
  "origin": "orchestrator-1",
  "scope": { "swarmId": "swarm-42", "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
  "correlationId": "attempt-001-aaaa-bbbb",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "data": {}
}
```

**Outcome (success)** (`kind=outcome`, `type=swarm-start`)
```json
{
  "timestamp": "2025-09-12T12:30:10Z",
  "version": "1",
  "kind": "outcome",
  "type": "swarm-start",
  "origin": "swarm-controller:swarm-42-marshal-1",
  "scope": { "swarmId": "swarm-42", "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
  "correlationId": "attempt-001-aaaa-bbbb",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "data": { "status": "Running", "retryable": false }
}
```

**Retry** (user clicks Retry)
```json
{
  "timestamp": "2025-09-12T12:30:20Z",
  "version": "1",
  "kind": "signal",
  "type": "swarm-start",
  "origin": "orchestrator-1",
  "scope": { "swarmId": "swarm-42", "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
  "idempotencyKey": "a1c3-1111-2222-9f",
  "correlationId": "attempt-002-cccc-dddd",
  "data": {}
}
```

**Duplicate handling (reality check)**

- Orchestrator REST endpoints are idempotent by `(swarmId, commandType, idempotencyKey)` and reuse the same `correlationId` when you retry with the same key.
- The Swarm Controller requires `idempotencyKey` to emit outcomes, but may still execute each signal attempt; treat idempotency as a *caller contract* unless a specific receiver documents deduplication.

---

## Implementation notes

- **Key scope:** if you implement caller-side deduplication, include the **signal name** in the key. Starting and stopping with the same idempotency key are **different** commands.
- **Retention:** caller-side caches should live for at least the maximum user retry window plus network jitter.
- **UI pattern:** send both fields, subscribe to `event.outcome.*` and `event.alert.*` (and `event.metric.*` when debugging), and reconcile outcomes by `correlationId`. Use `idempotencyKey` to spot accidental double-submits across retries.
- **If you collapse to one field:** only do so if clients truly reuse it on retries; otherwise you lose per-attempt tracing.

---

## Quick checklist

- [ ] Every control signal carries **both** fields.
- [ ] Outcomes echo **both** fields.
- [ ] Clients reuse the same `idempotencyKey` across retries of the same action.
- [ ] Logs index by `correlationId` (attempts) and by `idempotencyKey` (user actions).
