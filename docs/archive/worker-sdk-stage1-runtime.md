# Worker SDK Refactor — Stage 1 Runtime Skeleton

Stage 1 delivers the first executable runtime for the simplified PocketHive Worker SDK. The goal is to let worker services expose only the `GeneratorWorker` or `MessageWorker` interfaces while the SDK discovers them, constructs the invocation context, and routes messages.

## Runtime Building Blocks

| Component | Responsibility | Notes |
|-----------|----------------|-------|
| `WorkerDefinition` | Captures metadata from the `@PocketHiveWorker` annotation (role, type, queues, config class). | Normalises optional queue values and treats an unspecified config class as `Void`. |
| `WorkerRegistry` | Immutable collection of discovered workers, keyed by Spring bean name. | Annotations are resolved at startup; missing or duplicate beans cause immediate failure. |
| `DefaultWorkerContextFactory` | Creates a `WorkerContext` per invocation, wiring logger, metrics, observation registry, status publisher, and config snapshot. | Requires `swarmId` / `instanceId` from message headers or the configured `ControlPlaneIdentity`; fails fast when neither is supplied. |
| `WorkerInvocation` | Adapts the worker bean (`GeneratorWorker` or `MessageWorker`) to the runtime contract, handling status updates before/after execution. | Exceptions raise a FAILED status and bubble up to the caller for transport handling. |
| `DefaultWorkerRuntime` | Runtime façade used by transports to dispatch messages to worker beans. | Instantiated via Spring auto-configuration and caches invocation adapters for quick lookups. |

## Spring Boot Auto-Configuration

`PocketHiveWorkerSdkAutoConfiguration` now provides runtime beans:

- `StatusPublisher` — defaults to `StatusPublisher.NO_OP` but can be overridden by applications.
- `WorkerRegistry` — scans the `ApplicationContext` for beans annotated with `@PocketHiveWorker` and creates `WorkerDefinition` records.
- `WorkerContextFactory` — builds `DefaultWorkerContextFactory`, optionally reusing Micrometer `MeterRegistry` and `ObservationRegistry` if the application defines them. When a `ControlPlaneIdentity` bean is present (via the control-plane Spring starter), it is passed to the factory so swarm/instance identifiers are enforced.
- `WorkerRuntime` — assembles a `DefaultWorkerRuntime`, using the Spring bean factory as a resolver for worker and config beans.

The auto-configuration remains part of the existing worker starter so services receive the new runtime simply by upgrading the dependency.

### Annotation Usage

```java
@PocketHiveWorker(
    role = "generator",
    type = WorkerType.GENERATOR,
    outQueue = "work.generator.out"
)
public class ExampleGenerator implements GeneratorWorker {
    @Override
    public WorkResult generate(WorkerContext ctx) {
        return WorkResult.message(
            WorkMessage.text("hello from stage1").build()
        );
    }
}
```

At startup the auto-configuration registers this bean in the `WorkerRegistry`, builds a `WorkerInvocation`, and exposes it via `WorkerRuntime.dispatch(beanName, message)`.

## Status Propagation

The runtime triggers `StatusPublisher.update(..)` events for `STARTED`, `COMPLETED`, and `FAILED` phases on every invocation. Applications can supply a custom `StatusPublisher` bean to bridge these updates to control-plane status snapshots in Stage 2.

## Config Access

If the annotation specifies a configuration class, the runtime lazily resolves it from the Spring context and makes it available via `WorkerContext.config(Class)` for convenience. Stage 2 will integrate real control-plane config updates; for now, this enables manual or test-provided configs.

## Next Steps

- Wire the runtime into RabbitMQ consumers/producers so `WorkerRuntime.dispatch` is invoked by the transport layer.
- Implement the control-plane integration layer (config updates, ready/error/status) on top of the runtime.
- Extend `StatusPublisher` and `WorkerContext` with richer observability primitives as outlined in Stage 0.
