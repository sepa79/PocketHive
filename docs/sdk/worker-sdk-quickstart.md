# PocketHive Worker SDK Quick Start

This guide shows how to adopt the simplified Worker SDK introduced in the Stage 1–3 refactor. Pair it with the
[Stage 0 design](worker-sdk-stage0-design.md) and [Stage 1 runtime walkthrough](worker-sdk-stage1-runtime.md) when you need
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

Configure the control-plane identity and role using the shared properties:

```yaml
pockethive:
  control-plane:
    exchange: ph.control
    swarm-id: swarm-1
    worker:
      role: processor
      instance-id: processor-1
```

See the [control-plane worker guide](../control-plane/worker-guide.md) for full property reference.

## 2. Annotate worker beans

Annotate each business implementation with `@PocketHiveWorker`. Choose the worker type (`GENERATOR` or `MESSAGE`) and
provide routing metadata. Optional `config` classes participate in Stage 2 control-plane hydration.

```java
@Component("processorWorker")
@PocketHiveWorker(
    role = "processor",
    type = WorkerType.MESSAGE,
    inQueue = TopologyDefaults.MOD_QUEUE,
    outQueue = TopologyDefaults.FINAL_QUEUE,
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements MessageWorker {
  // business logic
}
```

Generator workers follow the same pattern but implement `GeneratorWorker` and omit `inQueue`.

## 3. Implement the worker interfaces

The Stage 1 runtime discovers annotated beans and invokes the corresponding business interface:

- `GeneratorWorker.generate(WorkerContext)` emits a single `WorkMessage` per invocation. Stage 2 control-plane updates
  determine how often `GeneratorRuntimeAdapter` schedules the call.
- `MessageWorker.onMessage(WorkMessage, WorkerContext)` receives inbound messages converted by the SDK transport and
  returns a `WorkResult` to publish downstream.

Use the `WorkerContext` to:

- Retrieve typed configuration supplied by Stage 2 control-plane commands (`context.config(MyConfig.class)`).
- Enrich Stage 2 status payloads via `context.statusPublisher()`.
- Access Stage 3 observability hooks (`observationRegistry`, `observabilityContext`). The runtime guarantees that
  `observabilityContext()` returns an initialised instance so workers can append hops without null checks.

Refer to the migrated [generator](../../generator-service/src/main/java/io/pockethive/generator/GeneratorWorkerImpl.java)
and [processor](../../processor-service/src/main/java/io/pockethive/processor/ProcessorWorkerImpl.java) services for
end-to-end implementations.

## 4. Dispatch work through the runtime adapters

Transport adapters inject the Stage 1 `WorkerRuntime` and Stage 2 `WorkerControlPlaneRuntime` beans. Message-based
services should now compose the reusable [`RabbitMessageWorkerAdapter`](../../common/worker-sdk/src/main/java/io/pockethive/worker/sdk/transport/rabbit/RabbitMessageWorkerAdapter.java)
instead of re-implementing listener toggling, Rabbit conversions, and control-plane validation.

```java
@Component
class ProcessorRuntimeAdapter implements ApplicationListener<ContextRefreshedEvent> {

  private static final Logger log = LoggerFactory.getLogger(ProcessorRuntimeAdapter.class);
  private final RabbitMessageWorkerAdapter delegate;

  ProcessorRuntimeAdapter(WorkerRuntime workerRuntime,
                          WorkerRegistry workerRegistry,
                          WorkerControlPlaneRuntime controlPlaneRuntime,
                          RabbitTemplate rabbitTemplate,
                          RabbitListenerEndpointRegistry listenerRegistry,
                          ControlPlaneIdentity identity,
                          ProcessorDefaults defaults) {
    WorkerDefinition definition = workerRegistry
        .findByRoleAndType("processor", WorkerType.MESSAGE)
        .orElseThrow();

    delegate = RabbitMessageWorkerAdapter.builder()
        .logger(log)
        .listenerId("processorWorkerListener")
        .displayName("Processor")
        .workerDefinition(definition)
        .controlPlaneRuntime(controlPlaneRuntime)
        .listenerRegistry(listenerRegistry)
        .identity(identity)
        .defaultEnabledSupplier(() -> defaults.asConfig().enabled())
        .desiredStateResolver(snapshot -> snapshot.enabled().orElse(defaults.asConfig().enabled()))
        .dispatcher(message -> workerRuntime.dispatch(definition.beanName(), message))
        .messageResultPublisher((result, outbound) -> {
          String routingKey = Optional.ofNullable(definition.outQueue()).orElse(Topology.FINAL_QUEUE);
          rabbitTemplate.send(Topology.EXCHANGE, routingKey, outbound);
        })
        .build();
  }

  @PostConstruct
  void initialise() {
    delegate.initialiseStateListener();
  }

  // Delegate @RabbitListener, control-plane, and scheduled hooks to the helper
}
```

The helper registers control-plane listeners, converts AMQP messages via `RabbitWorkMessageConverter`, and emits status
snapshots/deltas so service adapters can focus on wiring business-specific routing or result handling. See the updated
[`processor`](../../processor-service/src/main/java/io/pockethive/processor/ProcessorRuntimeAdapter.java),
[`moderator`](../../moderator-service/src/main/java/io/pockethive/moderator/ModeratorRuntimeAdapter.java), and
[`postprocessor`](../../postprocessor-service/src/main/java/io/pockethive/postprocessor/PostProcessorRuntimeAdapter.java)
adapters for complete examples.

Generator/trigger style adapters remain available when you need scheduling or fan-in behaviour that differs from the
message helper (for example, see the
[`GeneratorRuntimeAdapter`](../../generator-service/src/main/java/io/pockethive/generator/GeneratorRuntimeAdapter.java)
and [`TriggerRuntimeAdapter`](../../trigger-service/src/main/java/io/pockethive/trigger/TriggerRuntimeAdapter.java)).

## 5. Test with the SDK fixtures

Stage 1 introduced `ControlPlaneTestFixtures` and `WorkerSdkTestFixtures` to make unit tests deterministic. Use them to
construct canonical identities, topology descriptors, and sample payloads without repeating boilerplate.

```java
ControlPlaneProperties props = ControlPlaneTestFixtures.workerProperties("swarm-1", "processor", "processor-1");
WorkerRuntime runtime = WorkerSdkTestFixtures.runtime(applicationContext);
```

## 6. Observability and Stage 3 enhancements

Stage 3 enriches the runtime with Micrometer and Observation support. `WorkerContext.meterRegistry()` and
`WorkerContext.observationRegistry()` surface the shared registries, while `WorkMessage` builders accept an
`ObservabilityContext`. The SDK ensures that `WorkerContext.observabilityContext()` never returns {@code null} and
includes a trace id, hop list, and swarm id, making it safe to append hop metadata or forward the context as-is.
Use these hooks to emit custom metrics and propagate trace metadata as shown in the
[processor worker](../../processor-service/src/main/java/io/pockethive/processor/ProcessorWorkerImpl.java).

For the full roadmap and design rationale, review the [Worker SDK simplification plan](worker-sdk-simplification-plan.md).
