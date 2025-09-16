# Swarm Controller Service

Manages a single swarm by provisioning queues, launching bees, and relaying control signals.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Deduplication semantics

- The controller keeps an in-memory least-recently-used cache of the last 100 outcomes, keyed by
  `(swarmId, signal, role, instance, idempotencyKey)`. Older entries are evicted when the cache is full.
- A retry with the same idempotency key replays the cached `ev.ready.*` or `ev.error.*` confirmation without re-running the operation.
- Every duplicate attempt also emits `ev.duplicate.<signal>` containing:
  - the new `correlationId`,
  - the cached `originalCorrelationId`,
  - the original `idempotencyKey`, and
  - the resolved `scope` (swarm/role/instance).
  Consumers can use this notice to map the fresh attempt back to the cached confirmation.

