# PocketHive Worker SDK — v2 Autoflight Plan

> Scope: eliminate per-service runtime adapters by promoting input/output wiring,
> configuration, and worker metadata into the SDK. This doc captures the next stage
> after `worker-unification-plan.md`.

## Goals

1. **Autoconfiguration**  
   Discover `@PocketHiveWorker` beans automatically, create their inputs/outputs,
   and register them with the runtime — no more `*RuntimeAdapter` classes.

2. **Outputs as first-class citizens**  
   Mirror the `WorkInput` SPI with `WorkOutput`. Result routing (queues, HTTP, etc.)
   is expressed declaratively and handled by the SDK rather than hand-written
   `RabbitTemplate` code.

3. **Separate infra config from worker config**  
   Input/output plumbing (poll rates, Rabbit endpoints, batch sizes) should not live
   inside each worker’s domain config. Infrastructure knobs belong to the framework.

4. **Uniform configuration model**  
   Services define a single `@ConfigurationProperties` bean per worker role. The SDK
   binds it automatically and exposes it via `WorkerContext#config(...)`.

5. **Enrich worker metadata**  
   Workers describe their capabilities, description, and IO bindings once via
   `@PocketHiveWorker`. The control-plane configuration remains the source of truth
   for the worker role, and scenario-manager reads the combined metadata to expose
   it to the UI (so the UI no longer hard-codes which worker can do what).

6. **Remove RuntimeAdapter classes**  
   After the above pieces land, generator/moderator/processor/trigger services have
   no runtime adapters; they simply implement `PocketHiveWorkerFunction`.

## Proposed Workstream

### 1. Metadata & Annotation Enhancements

- Extend `@PocketHiveWorker`:
  - `String description()` — shown in status snapshots/UI.
  - `WorkerOutputType output()` — enum describing default output channel
    (e.g., `RABBITMQ`, `NONE`, future HTTP/Loki/etc).
  - Optional `Class<? extends WorkInputConfig>` / `Class<? extends WorkOutputConfig>`
    overrides for advanced scenarios.
- Introduce `WorkerCapabilities` descriptor (e.g., scheduler, http, streaming) to
  allow scenario-manager (and thus the UI) to reason about workers without manual
  wiring. Capabilities are emitted as part of status payloads.

### 2. Input & Output Config Abstractions

- Create `WorkInputConfig` / `WorkOutputConfig` marker interfaces with
  Spring-friendly `@ConfigurationProperties` implementations:
  - `SchedulerInputProperties`, `RabbitInputProperties`, etc.
  - `RabbitOutputProperties`, `HttpOutputProperties`, etc.
- Bind them under `pockethive.inputs.<inputType>` and
  `pockethive.outputs.<worker-role>` by default; allow overrides per worker via
  annotation attributes.
- Worker domain config becomes a separate bean, e.g.
  `@PocketHiveWorkerConfigProperties`.

### 3. Output SPI

- Define `WorkOutput` interface mirroring `WorkInput`:
  ```java
  interface WorkOutput {
      void publish(WorkResult.Message message, WorkerDefinition definition);
  }
  ```
- Implement default outputs:
  - `RabbitWorkOutput` (uses `RabbitTemplate` & output props).
  - `NoopWorkOutput` (for scheduler-only/test workers).
- Integrate with `WorkResult` handling inside the SDK so worker code simply returns
  `WorkResult.message(...)` and the output takes care of publishing.

### 4. Autoconfiguration Module

- Extend the existing worker SDK auto-configuration so it:
  - Scans for `@PocketHiveWorker` beans.
  - Resolves their definitions (`WorkerDefinition` already exists).
  - Determines the input/output types from annotation + config props.
  - Creates the `WorkInput` / `WorkOutput` pair via factories.
  - Registers them in `WorkInputRegistry` / upcoming `WorkOutputRegistry`.
- Keep using the existing `WorkInputLifecycle` to start/stop everything (inputs
  *and* outputs). This keeps enable/disable behaviour centralized and respects
  the same configuration toggles (which will move into the infra config section).
- Services no longer build adapters manually; they just supply the worker bean,
  config properties, and (if needed) custom input/output config classes.

### 5. Configuration Simplification

- Provide a base `PocketHiveWorkerProperties<T>` that workers can extend or reuse:
  ```java
  @PocketHiveWorkerConfigProperties
  public class GeneratorWorkerProperties extends PocketHiveWorkerProperties<GeneratorWorkerConfig> {}
  ```
- Auto-bind defaults via `@EnablePocketHiveWorkers` so the SDK registers them with
  the control plane (`registerDefaultConfig`).
- This removes `GeneratorDefaults`, `ModeratorDefaults`, etc.

### 6. Remove RuntimeAdapters

- Once auto-config is in place, delete per-service adapters and update services to:
  - Define their worker config bean (`@ConfigurationProperties`).
  - Implement `PocketHiveWorkerFunction`.
  - Optionally set annotation overrides (description, output type, etc.).
- Update docs and samples (remove old examples, plan to recreate new ones after the
  SDK stabilizes).

### 7. Follow-up / UI Integration

- Ensure `WorkerStatus` includes the new `description`, IO types, and capabilities
  so scenario-manager can surface them via its API and the UI can render smart
  controls automatically.
- Update UI dashboards to consume the richer metadata.

## Open Questions / Decisions Needed

1. **Registry naming** — Favor keeping separate registries (`WorkInputRegistry`
   + `WorkOutputRegistry`) to keep usage simple and aligned with the existing
   lifecycle hooks.
2. **Default config binding** — Enforce the convention (`pockethive.worker.*`)
   rather than allowing annotation overrides; keep the user experience simple.
3. **Backward deletion** — Breaking changes are acceptable as part of SDK v2; no
   backward compatibility required. Migration happens alongside the v2 rollout.
4. **Output extensibility** — Yes; align with inputs. Third parties can contribute
   custom outputs by exposing `WorkOutputFactory` beans, and the SDK autoconfig will
   pick them up when wiring workers.

## Next Steps

1. Prototype autoconfiguration in a feature branch:
   - Auto-discover workers.
   - Bind input config to existing Rabbit/Scheduler implementations.
   - Create minimal `RabbitWorkOutput`.
2. Draft new `@PocketHiveWorker` annotation + properties classes.
3. Migrate one service (e.g., generator) to validate the flow end-to-end.
4. Roll the change across other services, then remove runtime adapters entirely.
5. Refresh docs (`docs/sdk/…`, service READMEs) to describe the new flow.

## Implementation Tasks

1. **Metadata & Annotation**
   - [x] Extend `@PocketHiveWorker` with description, capabilities, output type, config overrides.
   - [x] Define `WorkerCapabilities` enum + incorporate into `WorkerDefinition`.
   - [x] Update `WorkerStatus` model and control-plane emissions to include description, IO, capabilities.

2. **Configuration Infrastructure**
   - [x] Introduce `PocketHiveWorkerProperties<T>` base class and bind `pockethive.worker.*` automatically. Services can extend the base, provide `role` + `configType`, and expose `@PocketHiveWorkerConfigProperties`. Defaults defined under `...enabled` / `...config.*` are auto-registered with the control plane for every worker sharing that role.
  - [x] Create `WorkInputConfig` / `WorkOutputConfig` marker interfaces and default property classes (e.g., scheduler + RabbitMQ) plus binders to resolve `pockethive.inputs/outputs.<type>` into typed configs.
  - [x] Move enable/disable flags + infra knobs out of worker domain configs into the new input/output properties.

3. **Output SPI**
   - [x] Define the `WorkOutput` interface and `WorkOutputFactory`.
   - [x] Implement default outputs (Rabbit, Noop) and register them (currently Noop by default; Rabbit factory wiring to follow).
   - [x] Integrate with `WorkResult` handling so results automatically publish through the selected output.

4. **Autoconfiguration**
   - [x] Add `WorkInputFactory` SPI + initializer so auto-config can resolve per-worker inputs (currently defaulting to noop until concrete factories are wired).
   - [x] Ensure lifecycle beans manage both inputs and outputs (registries + lifecycle hooks now exist for each, ready for wiring into factories).
  - [x] Provide scheduler/Rabbit `WorkInputFactory` implementations (auto-wired by default so every worker receives the correct transport without extra config).
 - [x] Support custom inputs/outputs via factories contributed as Spring beans (ordered lists + `Ordered` support for precedence).

5. **Service Migration**
   _Deferred until Task 2 completes._
   - [x] Convert generator service to the new model (scheduler/outputs auto-wired; legacy scheduling config removed).
   - [x] Convert moderator service to the new model (depends solely on auto-wired Rabbit input/output; legacy scheduling config removed).
   - [x] Convert processor service to the new model (scheduler enabled via `Application`; legacy `Scheduling` config removed).
   - [x] Convert postprocessor service to the new model (relies on auto-wired inputs/outputs with scheduler baked into the application).
   - [x] Convert trigger service to the new model (only built-in scheduler remains; redundant `Scheduling` config removed).
   - [x] Delete obsolete defaults classes and runtime adapters once migration is complete.

6. **Documentation & Examples**
   _Deferred until Task 2 completes._
   - [x] Update SDK docs, architecture references, and service READMEs.
