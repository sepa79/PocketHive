# `correlationId` vs `idempotencyKey`

**Goal:** make control signals safe to retry and easy to trace across services.

---

## TL;DR
- **`correlationId`** → trace **this attempt** end‑to‑end across services, logs, and spans.
- **`idempotencyKey`** → ensure the **same command** is not executed twice across **retries**.

Use **both** on every control signal.

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
- On duplicate: perform a **no‑op** and **replay** the previous outcome, or emit an identical confirmation.
- All confirmations **echo both** fields so UIs and services can stitch responses to the initiating attempt and safely ignore late duplicates.

### Delivery model
- Expect **at‑least‑once** delivery with AMQP. Do not rely on “exactly‑once”. Idempotency is the safety net.

---

## Example

**Signal**
```json
{
  "signal": "swarm-start",
  "swarmId": "swarm-42",
  "correlationId": "attempt-001-aaaa-bbbb",
  "idempotencyKey": "a1c3-1111-2222-9f"
}
```

**Confirmation (success)**
```json
{
  "result": "success",
  "signal": "swarm-start",
  "swarmId": "swarm-42",
  "state": "Running",
  "idempotencyKey": "a1c3-1111-2222-9f",
  "correlationId": "attempt-001-aaaa-bbbb",
  "ts": "2025-09-12T12:30:08Z"
}
```

**Retry** (user clicks Retry)
```json
{
  "signal": "swarm-start",
  "swarmId": "swarm-42",
  "idempotencyKey": "a1c3-1111-2222-9f",   // same
  "correlationId": "attempt-002-cccc-dddd"  // new
}
```

**Duplicate handling (server)**
- Lookup by `(swarmId, "swarm-start", "a1c3-1111-2222-9f")`.
- If found, **do not** re‑execute. Re‑emit the stored confirmation payload.

---

## Implementation notes

- **Key scope:** include the **signal name** in the dedup key. Starting and stopping with the same idempotency key are **different** commands.
- **Retention:** store idempotency entries for at least the maximum user retry window plus network jitter.
- **UI pattern:** send both fields, subscribe to `ev.ready.*` and `ev.error.*`, and reconcile confirmations by `correlationId`. Use `idempotencyKey` to resolve late or duplicated confirmations safely.
- **If you insist on one field:** you can collapse to `requestId` **only if** clients reuse it on retries and the server dedups on it. You will lose clean per‑attempt tracing unless you add a separate `attemptId` or tracing span IDs.

---

## Quick checklist

- [ ] Every control signal carries **both** fields.  
- [ ] Controller dedups on `(swarmId, signal, idempotencyKey)`.  
- [ ] Confirmations echo **both** fields.  
- [ ] Logs and metrics index by **correlationId** for attempts and by **idempotencyKey** for user actions.  
