# Removing `inQueue` / `outQueue` From `@PocketHiveWorker`

> Status: **implemented / archived**.  
> Superseded by: `docs/sdk/worker-single-io-plan.md` and `docs/toBeReviewed/worker-configurable-io-plan.md`.

> Goal: eliminate the annotation-level queue defaults and make scenario/plan data the sole source of truth—no silent fallbacks.

## 1. Inventory & Contracts
- [x] Enumerate every SDK touch point for `WorkerDefinition.inQueue/outQueue` (listener registration, output routing, WorkerInfo, status publishers, UI consumption) and document the replacement source for each (plan/env/control-plane signal).
- [x] Audit external consumers (scenario-manager, UI, docs/tests) to confirm they rely on plan metadata today; list any stragglers that still read the annotation.

### SDK touch points & replacement sources (2025-02-03)

| Area | Files | Why it used `inQueue/outQueue` | Future source of truth |
| --- | --- | --- | --- |
| Listener wiring | `common/worker-sdk/.../RabbitWorkInputListenerConfigurer.java` | Previously called `WorkerDefinition.inQueue()` to decide which queue the listener container binds to. | Use `RabbitInputProperties.getQueue()`/`WorkIoBindings.inboundQueue()` (set from `POCKETHIVE_INPUT_RABBIT_QUEUE`, which Swarm Controller maps from `SwarmPlan.bees[*].work.in`). |
| Output routing | `common/worker-sdk/.../RabbitWorkOutput.java`, `RabbitMessageWorkerAdapter` | Fell back to `WorkerDefinition.outQueue()` (and `exchange`) when the worker properties didn’t specify overrides. | Always read `RabbitOutputProperties.getRoutingKey()/getExchange` or the derived `WorkIoBindings.outbound*` values sourced from `pockethive.outputs.rabbit.*` (`POCKETHIVE_OUTPUT_RABBIT_*`, populated from `work.out`). |
| Worker context exposure | `DefaultWorkerContextFactory`, `WorkerInfo` | Previously used the definition queues so user code (`context.info().inQueue()`) saw annotation defaults. | `WorkerInfo` now hydrates from the resolved IO configs / `WorkIoBindings`, which already mirror the plan/env. |
| Runtime state & status payloads | `WorkerState`, `WorkerControlPlaneRuntime` | Mirrored `definition.inQueue/outQueue` into the status document for UI consumers. | Emit `queues.work` from the resolved IO config / plan metadata via `WorkIoBindings`, dropping legacy fields per `docs/spec/control-events.schema.json`. |
| Auto-configuration | `PocketHiveWorkerSdkAutoConfiguration` | Normalises `@PocketHiveWorker.inQueue/outQueue`, stashes them in `WorkerDefinition`, and only overrides them when `pockethive.inputs/outputs` are present. | Remove the annotation plumbing; fail fast unless the inputs/outputs configs (which already come from env -> plan) are set. |
| Tests | `DefaultWorkerRuntimeTest`, `WorkerInvocationTest`, `PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest`, `WorkerControlPlaneRuntimeTest`, etc. | Assert the current fallback behaviour. | Update tests to assert the IO configs/env vars are required; no references to annotation queues. |

The Swarm Controller already injects `POCKETHIVE_INPUT_RABBIT_QUEUE` / `POCKETHIVE_OUTPUT_RABBIT_*` via `SwarmLifecycleManager.applyWorkIoEnvironment`, so every worker receives the plan-derived queues today.

### External consumers / docs still mentioning annotation queues

- SDK docs & service workers: `common/worker-sdk/README.md`, `docs/sdk/worker-sdk-quickstart.md`, and core workers (`generator`, `moderator`, `processor`, `postprocessor`) still show `@PocketHiveWorker(... inQueue/outQueue ...)` and call `context.info().inQueue()`. These need to be rewritten to highlight the `pockethive.inputs/outputs` config instead.
- Runtime tests (`common/worker-sdk/src/test/**`) verify the old behaviour; they must be updated alongside the code changes above.
- UI/runtime consumers already rely on the richer `queues.work` payload (per `docs/spec/control-events.schema.json`), so no UI code reads the deprecated fields anymore; no additional consumers were found under `ui/` or `scenario-manager-service/`.

## 2. Control-Plane Source of Truth
- [x] Confirm `SwarmPlan`/worker templates already carry `inputQueue`, `outputRoutingKey`, and `outputExchange`; update contracts so they’re required for all workers.
- [x] Ensure the orchestrator/control-plane emits those values into worker env/config (no missing fields) and fails fast when they’re absent instead of falling back to annotations.

## 3. Runtime Refactor
- [x] Update `RabbitWorkInputListenerConfigurer` so it binds listeners using plan-provided queue names (via `WorkIoBindings.inboundQueue()`), not the annotation defaults.
- [x] Update `RabbitWorkOutput` to derive exchange/routing key from the plan/env (keeping explicit overrides) via `WorkIoBindings`, rather than any annotation fallback.
- [x] Teach `WorkerContext`/`WorkerInfo` to read `workIn/workOut` from the control-plane config; keep temporary compatibility shims until all workers run with plans.
- [x] Adjust status publishers and `WorkerControlPlaneRuntime` to emit queue metadata from the new fields, mirroring what the UI expects.

## 4. Deprecation & Removal
- [x] Remove `@PocketHiveWorker.inQueue/outQueue` from the SDK (annotation, docs, review checklist) and enforce lint/tests to catch regressions.
- [x] Once all runtime pieces ignore the annotation values, remove the fields from `PocketHiveWorker`, `WorkerDefinition`, tests, and examples; fail fast if a worker lacks plan routing data instead of synthesizing defaults.
- [x] Refresh documentation (SDK quickstart, architecture, UI/operator guides) to spell out the “plan-driven routing only” rule.

## 5. Verification & Rollout
- [x] Run local swarm/e2e tests with multiple scenarios to verify listener wiring, message routing, and status dashboards still work with only plan data.
- [x] Validate UI screens reflect the new metadata, and control-plane status payloads still show queues/exchanges.
- [x] After deployment, monitor for missing routing data and remove compatibility shims once all swarms run on the new contracts.
