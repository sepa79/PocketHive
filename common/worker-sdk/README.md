# PocketHive Worker SDK

The Worker SDK packages the Spring Boot auto-configuration, runtime loop, and testing helpers required to bootstrap PocketHive control-plane participants. It combines the shared topology descriptors, emitters, runtime dispatch, and AMQP infrastructure from the `control-plane-*` modules so new workers can be scaffolded with minimal ceremony.

To understand the evolution of the SDK, see the [simplification roadmap](../../docs/sdk/worker-sdk-simplification-plan.md) and the [Stage 1 runtime notes](../../docs/sdk/worker-sdk-stage1-runtime.md).

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

### `GeneratorWorker`

Generator workers periodically emit messages without a triggering inbound queue. The runtime calls `generate(WorkerContext)` on a schedule that honours per-worker quotas derived from control-plane config. Implementations build a `WorkMessage` and return it wrapped in `WorkResult.message(...)`.

```java
@Component("generatorWorker")
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
    outQueue = "ph.swarm-alpha.gen", // align with pockethive.control-plane.queues.generator
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements GeneratorWorker {

  private final GeneratorDefaults defaults;

  @Override
  public WorkResult generate(WorkerContext context) {
    GeneratorWorkerConfig config = context.config(GeneratorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    String outQueue = context.info().outQueue();
    context.statusPublisher()
        .workOut(outQueue)
        .update(status -> status.data("path", config.path()));
    return WorkResult.message(WorkMessage.json(buildPayload(config)).build());
  }
}
```

The full implementation lives in [`generator-service`](../../generator-service/src/main/java/io/pockethive/generator/GeneratorWorkerImpl.java).

> The concrete queue names in the annotation examples are illustrative. Configure the values via
> `pockethive.control-plane.queues.*` (or the corresponding environment variables) and keep the
> annotation in sync with that configuration.

### `MessageWorker`

Message workers react to inbound queues. The runtime maps RabbitMQ deliveries into `WorkMessage` instances and invokes `onMessage(WorkMessage, WorkerContext)`. The return value controls whether a response is published downstream.

```java
@Component("processorWorker")
@PocketHiveWorker(
    role = "processor",
    type = WorkerType.MESSAGE,
    inQueue = "ph.swarm-alpha.mod",   // matches pockethive.control-plane.queues.moderator
    outQueue = "ph.swarm-alpha.final", // matches pockethive.control-plane.queues.final
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements MessageWorker {

  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ProcessorWorkerConfig config = context.config(ProcessorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    String inQueue = context.info().inQueue();
    String outQueue = context.info().outQueue();
    context.statusPublisher()
        .workIn(inQueue)
        .workOut(outQueue)
        .update(status -> status.data("baseUrl", config.baseUrl()));
    WorkMessage enriched = invokeHttpAndEnrich(in, context, config);
    return WorkResult.message(enriched);
  }
}
```

See [`processor-service`](../../processor-service/src/main/java/io/pockethive/processor/ProcessorWorkerImpl.java) for the full example, including metrics and observability integration.

### `WorkMessage`

`WorkMessage` is the immutable representation of a worker payload (body, headers, charset, and optional `ObservabilityContext`). Builders support text, JSON, and binary bodies, plus accessors like `asJsonNode()` for consumers. All runtime adapters use `WorkMessage` as the canonical format when bridging transports.

### `WorkerContext`

Every invocation receives a `WorkerContext` that exposes topology metadata (`WorkerInfo`), typed configuration, logging, Micrometer/Observation registries, and the `StatusPublisher`. Use the context helpers to record metrics, enrich status snapshots, and access control-plane config documented in the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md).

### `WorkerRuntime`

Transports interact with the runtime through `WorkerRuntime.dispatch(beanName, WorkMessage)`. The `DefaultWorkerRuntime` is provided by auto-configuration and resolves worker beans, builds invocation contexts, runs interceptors, and translates results back to the transport-specific adapter. See the runtime adapters in the [trigger](../../trigger-service/src/main/java/io/pockethive/trigger/TriggerRuntimeAdapter.java) and [generator](../../generator-service/src/main/java/io/pockethive/generator/GeneratorRuntimeAdapter.java) services for integration patterns.

### `WorkerControlPlaneRuntime`

The control-plane runtime bridges the SDK with the control-plane topic. It applies config updates, maintains per-worker state, publishes ready/status events, and exposes listeners. Stage 2 and Stage 3 capabilities (config hydration, status deltas, observability scaffolding) are described in [worker-sdk-stage1-runtime.md](../../docs/sdk/worker-sdk-stage1-runtime.md) and extended in the [quick start](../../docs/sdk/worker-sdk-quickstart.md).

## Putting it together

1. Add the `worker-sdk` dependency to your service.
2. Annotate business beans with `@PocketHiveWorker`, selecting the worker type and queues.
3. Implement `GeneratorWorker` or `MessageWorker` interfaces and return `WorkResult` instances.
4. Inject `WorkerRuntime` and `WorkerControlPlaneRuntime` into your transport adapter to dispatch messages and surface control-plane state (see the `*RuntimeAdapter` classes in each migrated service).
5. Use `WorkerContext` for config, metrics, observability, and status reporting.

For a full walkthrough consult the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md), which links to Stage 1–3 features and migration tips.
