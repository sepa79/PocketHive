# PocketHive — IMPLEMENTATION CHECKLISTS

These checklists summarize the practical steps for each role. See `ARCHITECTURE.md` for background and reference.

---

## Orchestrator
- [ ] Launch Controller for `{swarmId}` and await `ev.ready.swarm-controller.<instance>`
- [ ] Send `sig.swarm-template.<swarmId>` with `enabled=false` for all components
- [ ] Track swarm FSM and timeouts; on error/timeout, mark **Failed** (do not delete)
- [ ] Handle `sig.swarm-start/stop/remove.<swarmId>` and `sig.config-update…`
- [ ] Consume only Controller’s `ev.status-{full|delta}.swarm-controller.<instance>` and lifecycle events
- [ ] Operator‑only retries; maintain idempotency & correlation
- [ ] Emit `ev.error.swarm-create.<swarmId>` on create failure
- [ ] On `ev.ready.swarm-remove.<swarmId>`, remove the Swarm Controller runtime unit and clear orchestration state

## Swarm Controller
- [ ] Provision components; verify **Actuator=UP**; maintain aggregate **Ready** with `enabled=false`
- [ ] Derive start/stop order from **queue I/O**; apply `enabled` changes
- [ ] Consume per‑component status; emit **swarm‑level** aggregates and lifecycle events
- [ ] Treat AMQP status as heartbeat; poll Actuator on staleness
- [ ] Control plane always on; deduplicate commands; validate scope `{swarmId}`
- [ ] Emit `ev.ready.*` / `ev.error.*` confirmations for handled signals

## Components
- [ ] Expose Actuator; emit `ev.status-{full|delta}`
- [ ] Apply `enabled` to workload only; keep control plane responsive
