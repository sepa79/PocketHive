# PocketHive Worker SDK Quick Start

This guide shows how to adopt the simplified Worker SDK introduced in the Stage 1–3 refactor. Pair it with the
Stage 0 design notes and Stage 1 runtime walkthrough in the documentation archive when you need
deeper architectural context.

## 1. Add the dependency and starter

Include the Worker SDK starter in your Spring Boot service. The starter composes the control-plane auto-configuration,
registers the Stage 1 `WorkerRuntime`, and wires the `WorkerControlPlaneRuntime` that powers Stage 2 status/config
synchronisation.

```xml
<dependency>
  <groupId>io.pockethive</groupId>
  <artifactId>worker-sdk</artifactId>
</dependency>
```

Configure the control-plane identity alongside the worker IO sections. Rabbit inputs/outputs now live under
`pockethive.inputs.<type>` / `pockethive.outputs.<type>`, so workers read their queue/exchange bindings directly from
application configuration instead of the old `pockethive.control-plane.queues.*` map.

```yaml
pockethive:
  control-plane:
    exchange: ph.control
    swarm-id: swarm-1
    instance-id: processor-1
    worker:
      role: ${POCKETHIVE_CONTROL_PLANE_WORKER_ROLE}
  inputs:
    rabbit:
      queue: ph.swarm-1.mod
  outputs:
    rabbit:
      exchange: ph.swarm-1.hive
      routing-key: ph.swarm-1.final
```

See the [control-plane worker guide](../control-plane/worker-guide.md#configuration-properties) for the full
`WorkerControlPlaneProperties` reference, including the environment variables that mirror the IO configuration.

## 2. Annotate worker beans

Annotate each business implementation with `@PocketHiveWorker`. Queue and transport metadata now
flows solely from the control-plane plan via the `pockethive.inputs/outputs.*` configuration
(or the matching environment variables), so the annotation only captures capabilities and
optional config. The worker role comes from `pockethive.control-plane.worker.role`
(injected through `POCKETHIVE_CONTROL_PLANE_WORKER_ROLE`). Optional `config` classes participate in
Stage 2 control-plane hydration.

```java
@Component("processorWorker")
@PocketHiveWorker(
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements MessageWorker {
  // business logic
}
```

Generator and trigger workers follow the same pattern. Their IO type (`SCHEDULER` vs `RABBITMQ`)
is selected via `pockethive.inputs.type` in configuration, not via annotation attributes.

> **Status topology note**
> The Worker SDK automatically mirrors plan-provided queues into the control-plane status payload via `statusPublisher().workIn(...)` and `statusPublisher().workOut(...)`. The legacy `inQueue` field in status events has been removed; consumers should rely on the richer `queues.work`/`queues.control` block instead.

## 3. Implement the worker interface

The runtime discovers annotated beans that implement `PocketHiveWorkerFunction` and invokes
`onMessage(WorkMessage, WorkerContext)` for each input message. Scheduler-driven inputs (such as the
generator/trigger schedulers) emit synthetic seed messages, so the `message` parameter is always non-null even when no
payload is supplied. The return value determines whether a downstream payload should be published.

Use the `WorkerContext` to:

- Retrieve typed configuration supplied by Stage 2 control-plane commands (`context.config(MyConfig.class)`).
- Enrich Stage 2 status payloads via `context.statusPublisher()`.
- Access Stage 3 observability hooks (`observationRegistry`, `observabilityContext`). The runtime guarantees that
  `observabilityContext()` returns an initialised instance so workers can append hops without null checks.

Refer to the migrated [generator](../../generator-service/src/main/java/io/pockethive/generator/GeneratorWorkerImpl.java)
and [processor](../../processor-service/src/main/java/io/pockethive/processor/ProcessorWorkerImpl.java) services for
end-to-end implementations.

## 4. Let the SDK wire inputs and outputs

The SDK provisions the correct `WorkInput`/`WorkOutput` pair for each annotated worker automatically. Rabbit-driven
workers receive the shared `RabbitWorkInput`/`RabbitWorkOutput`, while scheduler-driven roles (generator, trigger)
receive the built-in scheduler input.

Custom inputs remain possible via `WorkInputFactory` beans. The trigger worker keeps a bespoke factory because it combines
the scheduler with rate-limit state, but all other services rely on the SDK defaults:

```java
@Component
class TriggerWorkInputFactory implements WorkInputFactory {

  private final WorkerRuntime workerRuntime;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final ControlPlaneIdentity identity;
  private final TriggerWorkerProperties properties;

  TriggerWorkInputFactory(WorkerRuntime workerRuntime,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          ControlPlaneIdentity identity,
                          TriggerWorkerProperties properties) {
    this.workerRuntime = workerRuntime;
    this.controlPlaneRuntime = controlPlaneRuntime;
    this.identity = identity;
    this.properties = properties;
  }

  @Override
  public boolean supports(WorkerDefinition definition) {
    return definition.input() == WorkerInputType.SCHEDULER
        && "trigger".equalsIgnoreCase(definition.role());
  }

  @Override
  public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
    SchedulerInputProperties scheduling = config instanceof SchedulerInputProperties props
        ? props
        : new SchedulerInputProperties();
    TriggerSchedulerState schedulerState = new TriggerSchedulerState(properties, scheduling.isEnabled());
    Logger logger = LoggerFactory.getLogger(definition.beanType());
    return SchedulerWorkInput.<TriggerWorkerConfig>builder()
        .workerDefinition(definition)
        .controlPlaneRuntime(controlPlaneRuntime)
        .workerRuntime(workerRuntime)
        .identity(identity)
        .schedulerState(schedulerState)
        .scheduling(scheduling)
        .logger(logger)
        .build();
  }
}
```

The auto-configured helper registers control-plane listeners, converts AMQP messages via `RabbitWorkMessageConverter`,
publishes `WorkResult.Message` payloads to the traffic exchange declared in `WorkerControlPlaneProperties`, and emits status
snapshots/deltas for every worker. Only workers with unusual transports need to provide factories like the trigger example
above; generator, moderator, processor, and postprocessor all run on the shared factories described in
`docs/sdk/worker-autoconfig-plan.md`.

## 5. Test with control-plane fixtures

Stage 1 introduced `ControlPlaneTestFixtures` to make unit tests deterministic. Use them to construct canonical
identities, topology descriptors, and sample payloads without repeating boilerplate.

`ControlPlaneTestFixtures.workerProperties(...)` returns the validated `WorkerControlPlaneProperties` bean so tests
can assert the same contract that production workers consume, including traffic exchange and queue aliases.

```java
WorkerControlPlaneProperties props = ControlPlaneTestFixtures.workerProperties("swarm-1", "processor", "processor-1");
```

## 6. Observability and Stage 3 enhancements

Stage 3 enriches the runtime with Micrometer and Observation support. `WorkerContext.meterRegistry()` and
`WorkerContext.observationRegistry()` surface the shared registries, while `WorkMessage` builders accept an
`ObservabilityContext`. The SDK ensures that `WorkerContext.observabilityContext()` never returns {@code null} and
includes a trace id, hop list, and swarm id, making it safe to append hop metadata or forward the context as-is.
Use these hooks to emit custom metrics and propagate trace metadata as shown in the
[processor worker](../../processor-service/src/main/java/io/pockethive/processor/ProcessorWorkerImpl.java).

For the full roadmap and design rationale, review the Worker SDK simplification plan in the documentation archive.
