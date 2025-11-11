# PocketHive Worker SDK

The Worker SDK packages the Spring Boot auto-configuration, runtime loop, and testing helpers required to bootstrap PocketHive control-plane participants. It combines the shared topology descriptors, emitters, runtime dispatch, and AMQP infrastructure from the `control-plane-*` modules so new workers can be scaffolded with minimal ceremony.

To understand the evolution of the SDK, consult the simplification roadmap and the Stage 1 runtime notes in the documentation archive.

## Modules

* `io.pockethive.worker.sdk.autoconfigure.PocketHiveWorkerSdkAutoConfiguration` exposes the canonical control-plane beans for worker and manager roles.
* `io.pockethive.worker.sdk.runtime` provides the Stage 1–3 runtime (`WorkerRuntime`, `WorkerControlPlaneRuntime`, interceptors, and state store) that discovers worker beans and drives invocation.
* `io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures` provides pre-configured descriptors, identities, and property builders that make unit tests easier to wire.

Add the dependency to a worker service to automatically register the control-plane beans and runtime:

```xml
<dependency>
  <groupId>io.pockethive</groupId>
  <artifactId>worker-sdk</artifactId>
</dependency>
```

## Runtime APIs

### `PocketHiveWorkerFunction`

All workers implement `PocketHiveWorkerFunction`, exposing a single
`onMessage(WorkMessage, WorkerContext)` method. The inbound `WorkMessage` may be a seed emitted by
the scheduler input (for generator/trigger scenarios) or a real payload delivered by a transport
adapter such as RabbitMQ. Return `WorkResult.message(...)` to emit downstream work or
`WorkResult.none()` to suppress publishing.

```java
@Component("generatorWorker")
@PocketHiveWorker(
    role = "generator",
    input = WorkerInputType.SCHEDULER,
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements PocketHiveWorkerFunction {

  private final GeneratorWorkerProperties properties;

  GeneratorWorkerImpl(GeneratorWorkerProperties properties) {
    this.properties = properties;
  }

  @Override
  public WorkResult onMessage(WorkMessage seed, WorkerContext context) {
    GeneratorWorkerConfig config = context.config(GeneratorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    String outQueue = context.info().outQueue();
    context.statusPublisher()
        .workOut(outQueue)
        .update(status -> status.data("path", config.path()));
    return WorkResult.message(WorkMessage.json(buildPayload(config)).build());
  }
}
```

```java
@Component("processorWorker")
@PocketHiveWorker(
    role = "processor",
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private final ProcessorWorkerProperties properties;

  ProcessorWorkerImpl(ProcessorWorkerProperties properties) {
    this.properties = properties;
  }

  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ProcessorWorkerConfig config = context.config(ProcessorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    context.statusPublisher()
        .update(status -> status.data("queue", "ph.swarm-alpha.mod"))
        .update(status -> status.data("baseUrl", config.baseUrl()));
    WorkMessage enriched = invokeHttpAndEnrich(in, context, config);
    return WorkResult.message(enriched);
  }
}
```

The full implementations live in the `generator-service` and `processor-service` modules.

> Queue names come from the swarm plan (`SwarmPlan.bees[*].work`) and are injected by the Swarm Controller—scenario authors update them through the
> `workers.<role>.config` block instead of touching environment variables.
> Local runs can still rely on `pockethive.inputs.<type>` / `pockethive.outputs.<type>` for wiring, but the controller ignores ad-hoc env overrides once a swarm launches.

### `WorkMessage`

`WorkMessage` is the immutable representation of a worker payload (body, headers, charset, and optional `ObservabilityContext`). Builders support text, JSON, and binary bodies, plus accessors like `asJsonNode()` for consumers. Every `WorkInput`/`WorkOutput` implementation uses `WorkMessage` as the canonical format when bridging transports.

### `WorkerContext`

Every invocation receives a `WorkerContext` that exposes topology metadata (`WorkerInfo`), typed configuration, logging, Micrometer/Observation registries, and the `StatusPublisher`. Use the context helpers to record metrics, enrich status snapshots, and access control-plane config documented in the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md).

### `WorkerRuntime`

`WorkInput` implementations interact with the runtime through `WorkerRuntime.dispatch(beanName, WorkMessage)`. The `DefaultWorkerRuntime` is provided by auto-configuration and resolves worker beans, builds invocation contexts, runs interceptors, and translates results back to whatever transport created the message. Most services rely on the SDK’s built-in inputs (RabbitMQ and scheduler), but bespoke transports can inject `WorkerRuntime` and `WorkerControlPlaneRuntime` the same way the shared factories do.

### `WorkerControlPlaneRuntime`

The control-plane runtime bridges the SDK with the control-plane topic. It applies config updates, maintains per-worker state, publishes ready/status events, and exposes listeners. Stage 2 and Stage 3 capabilities (config hydration, status deltas, observability scaffolding) are detailed in the Stage 1 runtime notes within the documentation archive and extended in the [quick start](../../docs/sdk/worker-sdk-quickstart.md).

## Putting it together

1. Add the `worker-sdk` dependency to your service.
2. Annotate business beans with `@PocketHiveWorker`, selecting the appropriate input binding (Rabbit by default). Queue bindings are provided by the swarm plan via `pockethive.inputs/outputs.*`.
3. Implement `PocketHiveWorkerFunction` and return `WorkResult` instances.
4. Enable `pockethive.worker.inputs.autowire=true` (the default) so the SDK wires the Rabbit or scheduler inputs/outputs for each annotated worker. Only build custom `WorkInputFactory` implementations when you truly need a bespoke transport.
5. Use `WorkerContext` for config, metrics, observability, and status reporting.

For a full walkthrough consult the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md), which links to Stage 1–3 features and migration tips.
