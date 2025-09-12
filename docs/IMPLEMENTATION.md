# PocketHive — IMPLEMENTATION CHECKLISTS

## Orchestrator
- [ ] Launch Controller for `{swarmId}`; wait `ev.ready.swarm-controller.<instance>`.
- [ ] On handshake, emit **`ev.ready.swarm-create.<swarmId>`** (echo correlation/idempotency).
- [ ] Send `sig.swarm-template.<swarmId>` (components initially `enabled=false`); await `ev.ready.swarm-template.<swarmId>`.
- [ ] Handle `sig.swarm-start/stop/remove.<swarmId>` and `sig.config-update…`; await corresponding `ev.ready.*` or `ev.error.*`.
- [ ] Track timeouts; mark Failed; do **not** auto-delete.
- [ ] On `ev.ready.swarm-remove.<swarmId>`, remove the Swarm Controller runtime unit and clear orchestration state.
- [ ] Idempotency: dedup `(swarmId, signal, idempotencyKey)`; correlation new per attempt.

## Swarm Controller
- [ ] Apply plan; provision components; maintain aggregate.
- [ ] Derive order from queue I/O; enable/disable per signal.
- [ ] Emit swarm-level aggregates and heartbeats; poll Actuator if status stale.
- [ ] Control plane always on; deduplicate commands.

## Components
- [ ] Expose Actuator; emit `ev.status-{full|delta}`.
- [ ] Apply `enabled` to workload only; control plane stays responsive.
