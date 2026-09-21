# `correlationId` vs `idempotencyKey`

**Goal:** make every control-plane operation traceable, safe under at-least-once delivery and terminal exactly once from the caller's perspective.

---

## TL;DR

- **`idempotencyKey`** identifies one logical caller request. The caller creates it and reuses it when retrying that same request.
- **`correlationId`** identifies the single accepted execution of that request. The Orchestrator creates it once and returns the same value for every retry with the same idempotency key.
- The Orchestrator owns the operation record and is the only producer of its public terminal `event.outcome.*`.
- An executor reports internal evidence as `event.result.*`; it does not publish the public outcome.

Use both identifiers on every command signal, executor result and public outcome.

## Where these fields live

- Envelope definitions: `docs/spec/asyncapi.yaml` and `docs/spec/control-events.schema.json`.
- Both fields are top-level envelope fields.
- They are non-empty and identical across the `signal`, executor `result` and public `outcome` for one operation.
- Metrics and uncorrelated alerts carry explicit `null` values. A correlated alert echoes both operation identifiers.

---

## Identity and ownership

The Orchestrator reserves `(swarmId, operationType, target, idempotencyKey)` before dispatch and creates one `correlationId` for the accepted execution.

| Identifier | Created by | Stable across retry of the same request | Purpose |
|---|---|---|---|
| `idempotencyKey` | API caller | Yes | Deduplicate the caller's logical request. |
| `correlationId` | Orchestrator | Yes | Join the accepted operation, dispatch, executor result, outcome, journal and logs. |

A duplicate REST request with the same tuple returns the existing operation and its original `correlationId`. If the operation is terminal, the Orchestrator returns and may replay the stored public outcome. It never creates another execution and a receiver never silently drops a duplicate without returning existing evidence.

A deliberate new execution after a terminal retryable failure uses a new `idempotencyKey`, which creates a new operation and `correlationId`.

This contract intentionally does not model transport retries as separate domain attempts. Broker delivery and publish retries remain observable in transport logs/traces; they do not create new operation identities.

---

## Delivery and terminalization

- AMQP delivery is at least once; receivers deduplicate by operation type and idempotency key.
- A duplicate signal continues the existing in-flight execution or replays the stored executor result.
- The executor result must exactly match `swarmId`, command type, concrete target, `correlationId` and `idempotencyKey` before the Orchestrator may accept it.
- Only the Orchestrator moves the operation to a terminal state and publishes `event.outcome.*`.
- A late executor result after timeout is journaled but cannot change the terminal operation or cause a second public outcome.
- Retention covers at least the command timeout plus delivery jitter.
- Cross-process recovery after an Orchestrator or Controller restart is outside the current contract.

---

## Example

**Signal** (`kind=signal`, `type=swarm-start`)

```json
{
  "timestamp": "2026-07-22T12:30:08Z",
  "version": "2",
  "kind": "signal",
  "type": "swarm-start",
  "origin": "orchestrator-1",
  "scope": { "swarmId": "swarm-42", "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
  "correlationId": "operation-001",
  "idempotencyKey": "request-001",
  "data": {}
}
```

**Executor result** (`kind=result`, internal evidence)

```json
{
  "timestamp": "2026-07-22T12:30:10Z",
  "version": "2",
  "kind": "result",
  "type": "swarm-start",
  "origin": "swarm-controller:swarm-42-marshal-1",
  "scope": { "swarmId": "swarm-42", "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
  "correlationId": "operation-001",
  "idempotencyKey": "request-001",
  "data": {
    "status": "Succeeded",
    "retryable": false,
    "context": {
      "target": { "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
      "requestedWorkloadState": "RUNNING",
      "observedWorkloadState": "RUNNING",
      "nonConvergedWorkers": []
    }
  }
}
```

**Public outcome** (`kind=outcome`, emitted by the Orchestrator)

```json
{
  "timestamp": "2026-07-22T12:30:11Z",
  "version": "2",
  "kind": "outcome",
  "type": "swarm-start",
  "origin": "orchestrator-1",
  "scope": { "swarmId": "swarm-42", "role": "orchestrator", "instance": "orchestrator-1" },
  "correlationId": "operation-001",
  "idempotencyKey": "request-001",
  "data": {
    "status": "Succeeded",
    "retryable": false,
    "context": {
      "target": { "role": "swarm-controller", "instance": "swarm-42-marshal-1" },
      "requestedWorkloadState": "RUNNING",
      "observedWorkloadState": "RUNNING",
      "nonConvergedWorkers": []
    }
  }
}
```

Retrying the REST request with `idempotencyKey=request-001` returns `correlationId=operation-001`. It does not publish a new logical signal. A user-requested re-execution uses a new idempotency key.

---

## Checklist

- [ ] Reserve `(swarmId, operationType, target, idempotencyKey)` before dispatch.
- [ ] Echo identical identifiers on signal, result, correlated alerts and outcome.
- [ ] Match executor results by all operation identity fields.
- [ ] Replay existing terminal evidence for duplicates.
- [ ] Publish no more than one public terminal outcome per operation.
- [ ] Keep domain state such as `RUNNING` out of `data.status`; operation status is `Succeeded`, `Rejected`, `Failed` or `TimedOut`.
