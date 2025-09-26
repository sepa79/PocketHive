# PocketHive — IMPLEMENTATION CHECKLISTS

## Orchestrator
- [ ] Launch Controller for `{swarmId}`; wait `ev.ready.swarm-controller.<swarmId>.swarm-controller.<instance>`.
- [ ] On handshake, emit **`ev.ready.swarm-create.<swarmId>.orchestrator.ALL`** (echo correlation/idempotency).
- [ ] Send `sig.swarm-template.<swarmId>.swarm-controller.ALL` (components initially `enabled=false`); await `ev.ready.swarm-template.<swarmId>.swarm-controller.<instance>`.
- [ ] Handle `sig.swarm-start/stop/remove.<swarmId>.swarm-controller.ALL` and `sig.config-update…`; await corresponding `ev.ready.*` or `ev.error.*` with swarm/role/instance segments.
- [ ] Track timeouts; mark Failed; do **not** auto-delete.
- [ ] On `ev.ready.swarm-remove.<swarmId>`, remove the Swarm Controller runtime unit and clear orchestration state.
- [ ] Idempotency: ensure orchestrator avoids reusing `(swarmId, signal, idempotencyKey)` unless a replay is desired; generate a
      new `correlationId` per attempt.

## Swarm Controller
- [ ] Apply plan; provision components; maintain aggregate.
- [ ] Derive order from queue I/O; enable/disable per signal.
- [ ] Emit swarm-level aggregates and heartbeats; poll Actuator if status stale.
- [ ] Control plane always on; process every command attempt and emit a confirmation for each.

## Components
- [ ] Expose Actuator; emit `ev.status-{full|delta}`.
- [ ] Apply `enabled` to workload only; control plane stays responsive.
