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
`onMessage(WorkItem, WorkerContext)` method. The inbound `WorkItem` may be a seed emitted by
the scheduler input (for generator/trigger scenarios) or a real payload delivered by a transport
adapter such as RabbitMQ. Return a `WorkItem` instance to emit downstream work or `null`
to suppress publishing.

```java
@Component("generatorWorker")
@PocketHiveWorker(
    input = WorkerInputType.SCHEDULER,
    config = GeneratorWorkerConfig.class
)
class GeneratorWorkerImpl implements PocketHiveWorkerFunction {

  private final GeneratorWorkerProperties properties;

  GeneratorWorkerImpl(GeneratorWorkerProperties properties) {
    this.properties = properties;
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    GeneratorWorkerConfig config = context.config(GeneratorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    String outQueue = context.info().outQueue();
    context.statusPublisher()
        .workOut(outQueue)
        .update(status -> status.data("path", config.path()));
    return WorkItem.json(buildPayload(config)).build();
  }
}
```

```java
@Component("processorWorker")
@PocketHiveWorker(
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements PocketHiveWorkerFunction {

  private final ProcessorWorkerProperties properties;

  ProcessorWorkerImpl(ProcessorWorkerProperties properties) {
    this.properties = properties;
  }

  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    ProcessorWorkerConfig config = context.config(ProcessorWorkerConfig.class)
        .orElseGet(properties::defaultConfig);
    context.statusPublisher()
        .update(status -> status.data("queue", "ph.swarm-alpha.mod"))
        .update(status -> status.data("baseUrl", config.baseUrl()));
    WorkItem enriched = invokeHttpAndEnrich(in, context, config);
    return enriched;
  }
}
```

The full implementations live in the `generator-service` and `processor-service` modules.

> Queue names come from the swarm plan (`SwarmPlan.bees[*].work`) and are injected by the Swarm Controller—scenario authors update them through the
> `workers.<role>.config` block instead of touching environment variables. The worker role follows the same pattern:
> `pockethive.control-plane.worker.role` (sourced from `POCKETHIVE_CONTROL_PLANE_WORKER_ROLE`) is injected per container so annotations never hard-code
> routing identifiers.
> Local runs can still rely on `pockethive.inputs.<type>` / `pockethive.outputs.<type>` for wiring, but the controller ignores ad-hoc env overrides once a swarm launches.

### `WorkItem`

`WorkItem` is the immutable representation of a worker payload (body, headers, charset, and optional `ObservabilityContext`). Builders support text, JSON, and binary bodies, plus accessors like `asJsonNode()` for consumers. Every `WorkInput`/`WorkOutput` implementation uses `WorkItem` as the canonical format when bridging transports.

### `WorkerContext`

Every invocation receives a `WorkerContext` that exposes topology metadata (`WorkerInfo`), typed configuration, logging, Micrometer/Observation registries, and the `StatusPublisher`. Use the context helpers to record metrics, enrich status snapshots, and access control-plane config documented in the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md).

### `WorkerRuntime`

`WorkInput` implementations interact with the runtime through `WorkerRuntime.dispatch(beanName, WorkItem)`. The `DefaultWorkerRuntime` is provided by auto-configuration and resolves worker beans, builds invocation contexts, runs interceptors, and translates results back to whatever transport created the message. Most services rely on the SDK’s built-in inputs (RabbitMQ and scheduler), but bespoke transports can inject `WorkerRuntime` and `WorkerControlPlaneRuntime` the same way the shared factories do.

### `WorkerControlPlaneRuntime`

The control-plane runtime bridges the SDK with the control-plane topic. It applies config updates, maintains per-worker state, publishes ready/status events, and exposes listeners. Stage 2 and Stage 3 capabilities (config hydration, status deltas, observability scaffolding) are detailed in the Stage 1 runtime notes within the documentation archive and extended in the [quick start](../../docs/sdk/worker-sdk-quickstart.md).

## Putting it together

1. Add the `worker-sdk` dependency to your service.
2. Annotate business beans with `@PocketHiveWorker`, selecting the appropriate input binding (Rabbit by default). Queue bindings are provided by the swarm plan via `pockethive.inputs/outputs.*`.
3. Implement `PocketHiveWorkerFunction` and return `WorkItem` instances (or `null` for no output).
4. The SDK automatically wires the Rabbit or scheduler inputs/outputs for the registered `@PocketHiveWorker`. Only build custom `WorkInputFactory` implementations when you truly need a bespoke transport.
5. Use `WorkerContext` for config, metrics, observability, and status reporting.

For a full walkthrough consult the [Worker SDK quick start](../../docs/sdk/worker-sdk-quickstart.md), which links to Stage 1–3 features and migration tips.

### Templating interceptor context

The templating interceptor builds a compact context map for Pebble templates:

- `payload` – the current `WorkItem.payload()`
- `headers` – the current `WorkItem.headers()`
- `workItem` – the full immutable `WorkItem` (including steps and observability context) if you need deeper inspection
- `eval(expression)` – evaluates a constrained SpEL expression against the same values plus helpers:
  - Root: `workItem`, `payload`, `headers`, `now` (`Instant`), `nowIso` (UTC ISO string)
  - Functions (call with a leading `#`): `randInt(min,max)`, `randLong(min,max)` (pass large bounds as strings), `uuid()`, `md5_hex(value)`, `sha256_hex(value)`, `base64_encode(value)`, `base64_decode(value)`, `hmac_sha256_hex(key,value)`, `regex_match(input,pattern)`, `regex_extract(input,pattern,group)`, `json_path(payload,path)`, `date_format(instant,pattern)`
  - Example: `{{ eval('#md5_hex(payload)') }}` to hash the current payload
  - Type references, `new`, bean lookups, and arbitrary method calls are blocked; stick to property access and the provided helpers.

### Redis uploader interceptor (config-only)

The SDK ships a Redis uploader interceptor that can be enabled purely via config under
`pockethive.worker.config.interceptors.redisUploader`. It routes the selected payload to Redis
lists without changing worker code:

```yaml
pockethive:
  worker:
    config:
          interceptors:
            redisUploader:
              enabled: true
              host: redis
              port: 6379
              phase: AFTER                   # BEFORE or AFTER; default AFTER (runs after onMessage)
              sourceStep: FIRST              # or LAST; default FIRST
              pushDirection: RPUSH           # default; symmetric with redis input LPOP
              routes:                        # first match wins; optional
                - match: '^.*"status":\\s*200.*$'
                  list: ph:dataset:main
                - match: 'no money'
                  list: ph:dataset:topup
          fallbackList: ph:dataset:other # if omitted, uses the x-ph-redis-list header from the WorkItem; otherwise skips
```

If no route matches and `fallbackList` is blank, the interceptor falls back to the original list
name carried in the `x-ph-redis-list` header (emitted by the Redis dataset input). Leave `enabled=false`
to keep it dormant.
