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
   Workers describe their role, capabilities, description, and IO bindings once,
   via `@PocketHiveWorker`. Scenario-manager reads the same metadata to expose it
   to the UI (so the UI no longer hard-codes which worker can do what).

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
- Bind them under `pockethive.inputs.<worker-role>` and
  `pockethive.outputs.<worker-role>` by default; allow overrides per worker via
  annotation attributes.
- Worker domain config becomes a separate bean, e.g.
  `@ConfigurationProperties("pockethive.workers.<role>")`.

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
  @ConfigurationProperties("pockethive.workers.generator")
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
2. **Default config binding** — Enforce the convention (`pockethive.workers.<role>`)
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
