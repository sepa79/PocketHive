# Removing `inQueue` / `outQueue` From `@PocketHiveWorker`

> Goal: eliminate the annotation-level queue defaults and make scenario/plan data the sole source of truth—no silent fallbacks.

## 1. Inventory & Contracts
- [ ] Enumerate every SDK touch point for `WorkerDefinition.inQueue/outQueue` (listener registration, output routing, WorkerInfo, status publishers, UI consumption) and document the replacement source for each (plan/env/control-plane signal).
- [ ] Audit external consumers (scenario-manager, UI, docs/tests) to confirm they rely on plan metadata today; list any stragglers that still read the annotation.

## 2. Control-Plane Source of Truth
- [ ] Confirm `SwarmPlan`/worker templates already carry `inputQueue`, `outputRoutingKey`, and `outputExchange`; update contracts so they’re required for all workers.
- [ ] Ensure the orchestrator/control-plane emits those values into worker env/config (no missing fields) and fails fast when they’re absent instead of falling back to annotations.

## 3. Runtime Refactor
- [ ] Update `RabbitWorkInputListenerConfigurer` so it binds listeners using plan-provided queue names, not `definition.inQueue()`.
- [ ] Update `RabbitWorkOutput` to derive exchange/routing key from the plan/env (keeping explicit overrides), ignoring `definition.outQueue()/exchange`.
- [ ] Teach `WorkerContext`/`WorkerInfo` to read `workIn/workOut` from the control-plane config; keep temporary compatibility shims until all workers run with plans.
- [ ] Adjust status publishers and `WorkerControlPlaneRuntime` to emit queue metadata from the new fields, mirroring what the UI expects.

## 4. Deprecation & Removal
- [ ] Mark `@PocketHiveWorker.inQueue/outQueue` as deprecated in the SDK, update docs/review checklist to forbid new uses, and add lint/tests to catch regressions.
- [ ] Once all runtime pieces ignore the annotation values, remove the fields from `PocketHiveWorker`, `WorkerDefinition`, tests, and examples; fail fast if a worker lacks plan routing data instead of synthesizing defaults.
- [ ] Refresh documentation (SDK quickstart, architecture, UI/operator guides) to spell out the “plan-driven routing only” rule.

## 5. Verification & Rollout
- [ ] Run local swarm/e2e tests with multiple scenarios to verify listener wiring, message routing, and status dashboards still work with only plan data.
- [ ] Validate UI screens reflect the new metadata, and control-plane status payloads still show queues/exchanges.
- [ ] After deployment, monitor for missing routing data and remove compatibility shims once all swarms run on the new contracts.
