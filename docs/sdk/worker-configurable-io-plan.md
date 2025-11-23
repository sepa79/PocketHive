# Worker Configurable IO — Plan

Make worker input/output selectable by configuration (scenario/capabilities/env)
instead of being hardcoded in `@PocketHiveWorker`. Workers should declare
capabilities only; concrete IO types come from config.

---

## 0. High-level tasks

- [ ] Baseline & constraints
  - [ ] Map current IO wiring in worker-sdk (`WorkInput`, `WorkOutput`, autoconfig).
  - [ ] Document constraints from architecture:
    - Control-plane + topology (queues/exchanges/routing) remain plan-driven.
    - NFF: no implicit fallbacks or heuristic IO selection.
    - IO type is fixed for a running swarm (no live swaps).
- [x] IO configuration model
  - [x] Design a config model for IO in worker-sdk:
    - `pockethive.worker.io.input-type = RABBITMQ|REDIS_DATASET|SCHEDULER|NOOP`
    - `pockethive.worker.io.output-type = RABBITMQ|REDIS_DATASET|NOOP`
    - Type-specific blocks for settings live under `pockethive.inputs.*` / `pockethive.outputs.*`, no guessing.
  - [x] Decide how this maps to:
    - `application.yml` in each worker (all core workers now declare `pockethive.worker.io.*`).
    - capabilities manifests: IO type remains plan/topology driven; capability files focus on worker config knobs, not IO transport.
    - scenario manager config updates: continue to tune worker config only (no runtime topology changes).
- [x] Annotation + autoconfig evolution
  - [x] Extend/deprecate `@PocketHiveWorker`:
    - Added `ioFromConfig` flag to opt into config-driven IO.
    - Keep `input`/`output` enums as defaults/compatibility for now.
  - [x] Update `PocketHiveWorkerSdkAutoConfiguration` to:
    - Read the new IO config model via `WorkerIoProperties`.
    - Resolve effective IO types from `ioFromConfig` + config.
    - Preserve current behaviour when `ioFromConfig=false` and no config override is present.
- [ ] IO factory in worker-sdk
  - [ ] Introduce `IoConfig` / `IoProfile` in worker-sdk (no Spring).
  - [ ] Add a `WorkInputFactory` + `WorkOutputFactory` that:
    - Accept a resolved config object.
    - Return the appropriate impl (Rabbit, Redis dataset, Scheduler, NOOP).
  - [ ] Wire factories into Spring autoconfig via adapters only.
- [x] Reference worker migration
  - [x] Pick `processor-service` as the first worker:
    - Remove hardcoded `input = RABBITMQ`, `output = RABBITMQ` reliance.
    - Configure its IO through the new config model.
  - [ ] Update `processor.latest.yaml` capabilities so UI can configure IO type
    and any relevant settings (e.g. NOOP output, Redis input).
  - [ ] Add/adjust tests around processor IO wiring (unit + an e2e scenario).
- [x] Other workers (staged rollout)
  - [x] Generator
  - [x] Data Provider
  - [x] PostProcessor
  - [x] Moderator
  - [x] HTTP Builder
  - [x] Trigger
- [ ] Clean-up & deprecation
  - [ ] Mark `input`/`output` on `@PocketHiveWorker` as deprecated once all
    workers are on config-driven IO.
  - [ ] Update docs to show IO configuration is scenario/capability-driven.
  - [ ] Remove legacy IO wiring paths once 1–2 releases have shipped.

---

## 1. Baseline notes

- Control-plane and topology stay as they are today: queues/exchanges and
  routing keys are derived from the swarm plan and env; IO refactor must not
  introduce runtime topology changes.
- The goal is to let the same worker binary serve multiple IO setups (e.g.
  Rabbit in/Redis out vs. Redis in/NOOP out) by changing config only, without
  touching Java code or rebuilding images.
