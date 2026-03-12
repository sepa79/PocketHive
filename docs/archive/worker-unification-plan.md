# PocketHive Worker Interface Unification Plan

> Status: **implemented / archived**.  
> Superseded by: `docs/toBeReviewed/worker-configurable-io-plan.md` and the current Worker SDK docs.

## Context

PocketHive currently ships two worker contracts:
- `GeneratorWorker.generate(...)` driven by service-specific schedulers.
- `MessageWorker.onMessage(...)` invoked by RabbitMQ delivery adapters.

Generator and trigger services implement bespoke scheduler loops that fabricate a seed `WorkMessage` and forward it to the runtime, while the remaining workers rely on `RabbitMessageWorkerAdapter`. The split complicates onboarding, forces explicit `WorkerType` handling throughout the runtime, and makes it hard to plug non-Rabbit inputs.

We are ready to converge on a **single `onMessage()` contract** backed by pluggable inputs that emit synthetic work envelopes when needed (for example, a scheduler ticking with an empty payload).

## Goals

1. Replace `GeneratorWorker` / `MessageWorker` with a single worker interface invoked via `onMessage(WorkMessage, WorkerContext)`.
2. Introduce an extensible **Work Input** abstraction that feeds the runtime without requiring hard-coded RabbitMQ knowledge.
3. Retain generator/trigger behaviour via a scheduler-backed input that emits seeded `WorkMessage` envelopes (never `null`).
4. Simplify `@PocketHiveWorker` metadata so role-specific services focus on routing and config, not worker “types”.
5. Remove transitional compatibility code; no legacy interface support is required.

## Constraints & Invariants

- The runtime still needs a non-null `WorkMessage` to build `WorkerContext` (headers carry swarm/instance IDs).
- Control-plane enable/disable toggles and config updates must continue to steer input execution.
- Observability (MDC, Micrometer, ObservationRegistry) should remain transparent to worker code.
- RabbitMQ adapters stay the default input so existing deployments don’t change behaviour.

## Implementation Tasks

### 1. Work Input Foundation
- [x] Add the `WorkInput` SPI (start/stop, updateDesiredState, close) and the accompanying `WorkInputFactory` for per-worker construction.
- [x] Define the `WorkMessageDispatcher` callback and context interface so inputs can push `WorkMessage` instances into `WorkerRuntime.dispatch(...)`.
- [x] Register inputs alongside worker definitions in the runtime auto-configuration; ensure lifecycle hooks run on application start/stop.

### 2. Annotation & Definition Refactor
- [x] Add a `WorkerInputType` selector to `@PocketHiveWorker` and thread it through `WorkerDefinition` and the registry.
- [x] Remove the legacy `WorkerType` dependency and migrate runtime lookups to the new metadata.

### 3. Unified Worker Contract
- [x] Replace the `GeneratorWorker` / `MessageWorker` split with a single `PocketHiveWorkerFunction` exposing `onMessage(WorkMessage, WorkerContext)`.
- [x] Update `WorkerInvocation` to always call the unified method while preserving status/metrics instrumentation.
- [x] Modify the context factory to tolerate seed messages (inputs now populate `swarmId` / `instanceId`, with fallbacks verified in `DefaultWorkerContextFactoryTest`).

### 4. Input Implementations
- [x] Extract current `RabbitMessageWorkerAdapter` logic into a `RabbitWorkInput` implementing the new SPI (listener enablement, conversion, outbound publishing).
- [x] Introduce a `SchedulerWorkInput` that wraps existing generator/trigger state machines:
  - [x] Consume rate/interval config from `WorkerControlPlaneRuntime`.
  - [x] Generate seeded `WorkMessage` envelopes carrying swarm/instance headers.
  - [x] Emit work via the shared dispatcher on tick events.

### 5. Runtime Wiring Updates
- [x] Replace `GeneratorRuntimeAdapter` and `TriggerRuntimeAdapter` loops with instances of `SchedulerWorkInput`.
- [x] Update processor/moderator/post-processor adapters to rely on `RabbitWorkInput` without custom wrappers.
- [x] Ensure control-plane state listeners feed the chosen `WorkInput` instances (enable flags, config snapshots).

### 6. Worker & Config Migration
- [x] Convert all worker implementations (`generator`, `moderator`, `processor`, `postprocessor`, `trigger`) to the unified interface and remove `type = ...` annotation usage.
- [x] Rework default config classes where needed so scheduler inputs still expose `ratePerSec`, `intervalMs`, etc.
- [x] Update control-plane tests and fixtures to remove `WorkerType` assertions.

### 7. Testing & Documentation
- [x] Refresh unit/integration tests across services and the SDK to cover the new input abstraction.
- [x] Add targeted tests for `SchedulerWorkInput` behaviour via generator/trigger adapter suites (rate, single-shot, enable toggles).
- [x] Update `docs/sdk/worker-sdk-quickstart.md`, service READMEs, and architectural references to describe the single interface + input bindings.

### 8. Cleanup
- Remove deprecated classes (`GeneratorWorker`, runtime adapters superseded by inputs, WorkerType enum).
- Audit for dead code (e.g., generator-specific state structures kept elsewhere) and prune them.
- Confirm Maven modules compile and existing CI workflows need no additional wiring.

## Follow-Up Considerations

- Future inputs (HTTP webhook, file tailing, etc.) can implement the same SPI; document extension points once the refactor lands.
- Consider exposing a builders or helper for custom inputs so external teams can plug in without touching internal runtime state.
