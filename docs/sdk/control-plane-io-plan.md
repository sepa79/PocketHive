# Control-Plane-Driven IO (Inputs/Outputs) — Plan

> Status: **future / design**.  
> IO v2 where the control plane owns IO config; not implemented yet.

This document outlines a future refactor (beyond the current env-mapping
approach) where worker IO configuration is owned entirely by the control
plane, without relying on Spring environment properties for IO type and
transport details.

The goal is to make scenarios + config-updates the single source of truth for
all worker IO, while keeping the Worker SDK simple and predictable.

---

## 0. High-level tracking

- [ ] Model & contracts:
  - [ ] Define a canonical IO config shape for workers (input/output) in the
        control-plane model.
  - [ ] Ensure scenarios and capability manifests expose this shape.
- [ ] Worker SDK IO binding:
  - [ ] Introduce an IO config abstraction in the SDK that can be populated
        from `WorkerStateSnapshot.rawConfig()`.
  - [ ] Decouple IO type resolution from Spring `@ConfigurationProperties`.
- [ ] IO factories & runtime:
  - [ ] Teach `WorkInputRegistryInitializer` / `WorkOutputRegistryInitializer`
        to build inputs/outputs from control-plane IO config.
  - [ ] Define how IO can react (or not) to live config changes.
- [ ] Migration & compatibility:
  - [ ] Provide a safe migration path from env-based IO to control-plane IO.
  - [ ] Keep behaviour deterministic and avoid implicit fallbacks.

---

## 1. Model: IO config in control plane

### 1.1 Canonical IO definition

- Define a small, transport-agnostic IO descriptor in the control-plane model
  (likely in `common/swarm-model` or `control-plane-core`), e.g.:
  - `InputDescriptor`:
    - `type` — enum (`RABBITMQ`, `SCHEDULER`, `REDIS_DATASET`, ...)
    - `config` — transport-specific map or typed sub-object
  - `OutputDescriptor`:
    - `type` — enum (`NONE`, `RABBITMQ`, ...)
    - `config` — transport-specific map/typed sub-object.
- Scenarios and capability manifests map directly to this IO descriptor.

### 1.2 Scenario → IO mapping

- Scenario Manager and Orchestrator:
  - Already treat `bee.config` as a generic map. For IO v2, we should:
    - Reserve `config.io.input` / `config.io.output` (or reuse
      `config.inputs` / `config.outputs`) as the canonical IO config block.
    - Validate that this block conforms to the IO descriptor schema.
- Control plane messages:
  - Ensure `config-update` payloads may override IO fields explicitly (e.g.
    change `type` or transport options) if we choose to allow runtime IO
    reconfiguration.

---

## 2. Worker SDK: bind IO from control plane

### 2.1 IO config abstraction

- Introduce a small SDK-side representation of IO config:
  - `WorkerIoConfig`:
    - `WorkerInputType inputType`
    - `WorkInputConfig inputConfig` (typed, e.g. `RabbitInputProperties`, `SchedulerInputProperties`, ...)
    - `WorkerOutputType outputType`
    - `WorkOutputConfig outputConfig` (typed, e.g. `RabbitOutputProperties`, ...)
- This is analogous to today’s `WorkIoBindings` + `WorkInputConfig` /
  `WorkOutputConfig`, but:
  - Populated from control-plane config rather than environment.
  - Potentially created per worker at runtime using `WorkerStateSnapshot.rawConfig()`.

### 2.2 Remove IO-type coupling to Spring env

- Current behaviour:
  - `WorkerInputTypeProperties` / `WorkerOutputTypeProperties` bind
    `pockethive.inputs.type` / `pockethive.outputs.type` from env.
  - `PocketHiveWorkerSdkAutoConfiguration` calls
    `resolveEffectiveInputType` / `resolveEffectiveOutputType` using those
    beans when building `WorkerDefinition`s.
- IO v2 target:
  - `WorkerDefinition` no longer needs a fixed `input`/`outputType` from env.
  - Instead:
    - It can carry a *default* IO type (from annotation or env) used only when
      control-plane IO is absent.
    - Or it can carry no IO type at all, and rely on control-plane IO to be
      present.
  - IO type for a given worker instance is resolved by combining:
    - `WorkerDefinition` (role, bean name, capabilities).
    - The effective IO config from the control-plane bootstrap payload.

### 2.3 Lifecycle and timing

- IO must be ready when:
  - The worker begins listening for work, and
  - The control-plane runtime expects the worker to send status.
- Approach:
  - Use the control-plane bootstrap config (already sent via `ConfigFanout`)
    to provision IO:
    - Worker SDK can:
      - Defer IO creation until the first bootstrap config for that worker
        arrives.
      - Or require bootstrap IO config to be present before the runtime is
        considered ready.
  - Ensure we avoid circular dependencies (IO creation should not require an
    already-active control-plane listener where possible).

---

## 3. IO factories & runtime

### 3.1 WorkInputRegistryInitializer / WorkOutputRegistryInitializer

- Current pattern:
  - Registry initializers iterate over `WorkerDefinition`s and use
    `WorkInputFactory` / `WorkOutputFactory` to create inputs/outputs based on
    annotation/env-derived IO type + bound properties.
- IO v2:
  - Factory signatures become:
    - `boolean supports(WorkerDefinition definition, WorkerIoConfig ioConfig)`
    - `WorkInput create(WorkerDefinition definition, WorkerIoConfig ioConfig)`
  - Initializers:
    - Resolve `WorkerIoConfig` for each worker from:
      - Control-plane bootstrap IO config, or
      - Defaults (annotation/env) if bootstrap is absent.
    - Use the IO config when selecting and constructing factory instances.

### 3.2 IO reconfiguration

- Decide scope:
  - **Conservative**: IO type and transport settings are immutable after
    startup; control-plane config-updates affect only worker-domain config.
  - **Flexible**: allow some IO fields to change at runtime (e.g. Redis host
    or listName) but keep IO *type* immutable.
- Implementation options:
  - Immutable IO type:
    - Factories are called once at startup; IO instances consult mutable
      config (like `RedisDataSetInputProperties`) that can be updated via
      control-plane.
  - Mutable IO type (harder, likely v3+):
    - IO registry would need to tear down and recreate inputs/outputs when
      the type changes.

---

## 4. Migration strategy

### 4.1 Phased rollout

1. **Phase 1 (current work)**:
   - Scenario → env mapping in Swarm Controller for IO type and transport
     details (scheduler, Redis, Rabbit).
   - Worker SDK still binds IO from env (`@ConfigurationProperties`).

2. **Phase 2 (introduce IO descriptor)**:
   - Add IO descriptor to control-plane model and scenarios.
   - Keep env mapping but start propagating IO descriptor alongside.

3. **Phase 3 (SDK consumes IO descriptor)**:
   - Worker SDK gains `WorkerIoConfig` abstraction populated from
     `WorkerStateSnapshot.rawConfig()`.
   - IO factories accept IO descriptor and use it as source of truth.
   - Env-based IO remains as fallback only for older controllers / scenarios.

4. **Phase 4 (deprecate env-based IO)**:
   - Deprecate `WorkerInputTypeProperties` / `WorkerOutputTypeProperties` in
     favour of the control-plane IO descriptor.
   - Remove env-based IO resolution once scenarios + controllers are fully
     on IO v2.

### 4.2 Compatibility guarantees

- Maintain:
  - Existing IO semantics for current scenarios (Rabbit queues derived from
    `work.in/out` port maps, scheduler defaults, etc.).
  - Backwards-compatible behaviour for workers that don’t yet supply IO in
    control-plane config.
- New behaviour:
  - When IO descriptor is present in control-plane config, it takes precedence
    over env-based IO for that worker.

---

## 5. Open questions

- Should IO descriptor live in swarm model (`Bee`) or as a separate control
  plane concept (e.g. attached to worker role/instance IDs)?
- Do we ever allow changing IO *type* at runtime, or do we keep type fixed
  and only allow transport options to change?
- How should status/error reporting distinguish between IO wiring problems
  (e.g. queue missing) and worker-domain errors?
