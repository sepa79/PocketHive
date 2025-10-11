# Worker SDK Refactor — Stage 0 Design

## Purpose
Establish the developer-facing contracts for refactoring PocketHive worker services so that every worker exposes a minimal business API while the Worker SDK handles infrastructure. This document captures the Stage 0 design decisions that unlock subsequent implementation stages.

## Worker Categories
- **Generator workers**: have no inbound work queue; produce messages on an interval or based on control-plane configuration. They expose a single `generate(WorkerContext ctx)` method.
- **Message workers**: consume messages from an inbound queue and forward to an outbound queue after business processing. They expose `onMessage(WorkMessage in, WorkerContext ctx)` and return the next payload (or `WorkResult.none()` to drop).
- **Async/streaming variants**: deferred until later stages. Stage 0 only defines synchronous interfaces so that Stage 1 can implement them.

## Public API Contracts

### 1. WorkMessage
A transport-agnostic message wrapper shared across workers, encapsulating:
- `byte[] body` plus helpers for JSON/text access (`asJson(Class<T>)`, `asString()`).
- Headers map (`Map<String, Object> headers`) with convenience getters for correlation/idempotency IDs.
- Observability context accessor (`ObservabilityContext context()`) — deferred to Stage 3 implementation.
- Fluent builder `WorkMessage.Builder` with factory shortcuts: `json(Object)`, `text(String)`, `binary(byte[])`.
- Metadata immutability; builders produce immutable instances.

### 2. WorkerContext
Injected per invocation, providing:
- `WorkerInfo info()` — role, swarmId, instanceId, queue names.
- `WorkerConfigView config(Class<C>)` — resolves latest config payload into a user-defined record/POJO via Jackson.
- `StatusPublisher statusPublisher()` — append worker-specific diagnostics to heartbeat snapshots.
- `Logger log()` — role-scoped logger.
- `MeterRegistry meters()` and `CounterBuilder counter(String name)` convenience wrappers.
- `ObservationFacade observation()` — optional trace/span helpers (deferred to Stage 3 for implementation).

### 3. Business Interfaces
```java
public interface GeneratorWorker {
    WorkResult generate(WorkerContext ctx) throws Exception;
}

public interface MessageWorker {
    WorkResult onMessage(WorkMessage in, WorkerContext ctx) throws Exception;
}
```

Where `WorkResult` is a simple sealed interface:
- `WorkResult.message(WorkMessage msg)` — emit downstream.
- `WorkResult.none()` — acknowledge without forwarding.
- Stage 3+: `WorkResult.batch(List<WorkMessage>)` for fan-out (optional).

### 4. Worker Descriptor
Declare worker metadata via annotation or configuration record. Stage 1 will prefer an annotation:
```java
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
    outQueue = Topology.GEN_QUEUE
)
```
For message workers, specify `inQueue` and `outQueue`. The runtime mirrors these bindings into the status payload under `queues.work`, replacing the legacy `inQueue` status field. Descriptor also references the control-plane descriptor by role (no per-service overrides needed).

## Control-Plane Behaviour
- Worker runtime owns a `ControlPlaneRuntime` component that:
  - Registers a listener for `config-update`, `status-request`, and lifecycle signals.
  - Converts config payload to the worker-specific config record defined in the descriptor.
  - Emits ready/error confirmations using the shared `ConfirmationSupport` helpers.
- Workers receive updated config via `WorkerContext.config()` without handling JSON manually.
- Status emission occurs automatically on interval; the worker can contribute additional fields using `statusPublisher().update(builder -> builder.data("foo", value))`.

## Configuration Schema
- Each worker declares a config record, e.g.:
  ```java
  public record GeneratorConfig(double ratePerSec, boolean enabled, String path, String method,
                                Map<String, String> headers, String body) {}
  ```
- Control-plane runtime uses Jackson to convert the `args` payload (Map) into the record, applying defaults via record canonical constructor.
- Validation is performed with Jakarta Bean Validation annotations if present; violations cause `emitConfigError` with detailed codes.
- Config instances are versioned; the runtime keeps the latest snapshot and exposes it via `WorkerContext.config()`.

## Error Handling
- Business methods can throw; runtime catches exceptions, emits control-plane error confirmation (phase `execute`), and logs context.
- `WorkResult` factories enforce non-null bodies; runtime validates outputs and logs diagnostic information on missing/invalid responses.

## Observability & Metrics
- Runtime manages `ObservabilityContext` propagation: inbound messages automatically hydrate MDC, and outbound messages carry updated context.
- Generators obtain fresh context per emission via `WorkerContext.observation().startSpan()` (implemented in Stage 3).
- Metrics baseline: per-worker TPS counter and duration timer. Additional metrics can be registered via `WorkerContext.meters()`.

## Architectural Alignment
- Routes, queue names, and control-plane descriptors remain defined in `common/control-plane-core`. The new runtime consumes existing descriptors, ensuring no contract drift.
- Duplicate signal suppression and self-filter remain active via `ControlPlaneConsumer` defaults.
- Security/auth boundaries unchanged; runtime only wraps existing components.

## Open Items for Stage 1
1. Implement concrete classes for `WorkMessage`, `WorkResult`, `WorkerContext`, and annotation processing.
2. Extend Worker SDK auto-configuration to detect beans implementing `GeneratorWorker` or `MessageWorker` and wire them into the runtime.
3. Decide on serialization modules for config records (Jackson parameter names module, Java 21 record support already in baseline).
4. Provide testing harness to simulate control-plane updates and queue messages.

## Non-Goals in Stage 0
- Async/reactive worker support.
- Batch message emission.
- Changes to routing contracts or control-plane message formats.

Stage 0 concludes with this design document and approval to proceed with Stage 1 implementation work.
