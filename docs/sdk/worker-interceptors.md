# PocketHive Worker Interceptors

This document explains the Worker SDK's invocation interceptors: what they are, how they are ordered, and how to plug in
your own cross-cutting logic such as metrics, tracing, or templating.

## 1. Invocation pipeline overview

At runtime, each `@PocketHiveWorker` bean is wrapped in a small pipeline:

1. A work input (Rabbit, scheduler, etc.) deserialises a message into a `WorkItem`.
2. The `WorkerRuntime` builds a `WorkerInvocationContext` with:
   - the `WorkerDefinition` (role, IO, config type),
   - the `WorkerState` (status/config snapshot), and
   - the `WorkerContext` (config, status publisher, observability, logger).
3. A chain of `WorkerInvocationInterceptor`s is applied around the worker's `onMessage` method.
4. The worker implementation runs and returns a `WorkItem` (or `null`) for downstream publishing.

Conceptually:

```text
WorkInput → WorkerInvocationContext → [Interceptor1 → Interceptor2 → … → Worker.onMessage] → WorkItem → WorkOutput
```

Interceptors are ordered. The SDK uses `Ordered` to ensure that observability concerns wrap the worker consistently:

- `WorkerObservabilityInterceptor` (`Ordered.HIGHEST_PRECEDENCE`)
- user-defined interceptors (if any)
- `WorkerMetricsInterceptor` (`Ordered.LOWEST_PRECEDENCE`)

## 2. Built-in interceptors

### 2.1 `WorkerObservabilityInterceptor`

Location: `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/WorkerObservabilityInterceptor.java`

Responsibilities:

- Ensure each worker has an `ObservabilityContext` with a hop history.
- Push trace/swarm metadata into MDC for log correlation.
- Append a new hop for the current worker role and instance.
- Attach the updated `ObservabilityContext` back to the resulting `WorkItem`.

Flow:

1. Read `ObservabilityContext` from `context.workerContext().observabilityContext()`.
2. Create a new `Hop` with:
   - `service = workerContext.info().role()`
   - `instance = workerContext.info().instanceId()`
   - `receivedAt = now`
   - `processedAt = null`
3. Append the hop to `context.observabilityContext().getHops()`.
4. Save existing MDC values for `traceId` and `swarmId`, then call
   `ObservabilityContextUtil.populateMdc(observabilityContext)`.
5. Invoke `chain.proceed(context)`.
6. On success or failure:
   - set `hop.processedAt = now`,
   - wrap the `WorkItem` with `item.toBuilder().observabilityContext(context).build()`.
7. Restore previous MDC values so the trace does not leak to unrelated code.

This interceptor is always present; workers can assume that `WorkerContext.observabilityContext()` is non-null and that
loggers see `traceId` and `swarmId` from the current context.

### 2.2 `WorkerMetricsInterceptor`

Location: `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime/WorkerMetricsInterceptor.java`

Responsibilities:

- Record per-worker invocation latency using Micrometer.
- Tag timings with worker role, instance, and outcome.

Flow:

1. Start a `Timer.Sample` using the shared `MeterRegistry`.
2. Invoke `chain.proceed(context)` and track whether it completes without throwing.
3. In a `finally` block, stop the timer and record a metric:
   - name: `pockethive.worker.invocation.duration`
   - tags:
     - `role` – worker role (e.g. `processor`, `generator`),
     - `worker` – instance id,
     - `outcome` – `success` or `error`.

This interceptor is auto-registered only when:

- a `MeterRegistry` bean is present, and
- `pockethive.control-plane.worker.metrics.enabled=true`.

When disabled, the worker pipeline still runs but invocation timings are not recorded.

## 3. Execution order and composition

Interceptors implement `WorkerInvocationInterceptor` and optionally `Ordered`. The runtime applies them in ascending
order, with the worker at the end of the chain. For example:

```text
WorkerObservabilityInterceptor (order = HIGHEST_PRECEDENCE)
  → CustomInterceptor (order = 0)
    → WorkerMetricsInterceptor (order = LOWEST_PRECEDENCE)
      → worker.onMessage(...)
```

In code, this is roughly equivalent to:

```java
WorkItem result =
    observabilityInterceptor.intercept(context, ctx1 ->
        customInterceptor.intercept(ctx1, ctx2 ->
            metricsInterceptor.intercept(ctx2, ctx3 ->
                worker.onMessage(ctx3.message(), ctx3.workerContext())
            )
        )
    );
```

Each interceptor can:

- inspect or mutate the `WorkerInvocationContext`,
- wrap execution in a `try/finally` block,
- short-circuit the call (e.g. for rate limiting), or
- decorate the returned `WorkItem` before it reaches the output.

## 4. Writing a custom interceptor

To attach additional cross-cutting behaviour, implement `WorkerInvocationInterceptor` and register it as a Spring bean.

Example: simple logging interceptor that records start/stop events per worker.

```java
@Component
class LoggingInterceptor implements WorkerInvocationInterceptor, Ordered {

  private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

  @Override
  public WorkItem intercept(WorkerInvocationContext context, Chain chain) throws Exception {
    var info = context.workerContext().info();
    log.debug("Worker {}({}) starting", info.role(), info.instanceId());
    try {
      return chain.proceed(context);
    } finally {
      log.debug("Worker {}({}) finished", info.role(), info.instanceId());
    }
  }

  @Override
  public int getOrder() {
    return 0; // between observability (high) and metrics (low)
  }
}
```

Because interceptors are applied generically, this interceptor will wrap *all* workers in the service, regardless of
role or input type.

## 5. When to customise

Interceptors are a good fit for:

- metrics that span all workers (e.g. centralised SLOs),
>- log enrichment beyond the default MDC population,
>- generic transformations such as templating (`TemplatingInterceptor`),
>- rate limiting or circuit-breaking policies.

They are **not** intended for worker-specific business logic. When in doubt:

- keep business rules inside `onMessage`, and
- reserve interceptors for behaviour that should apply uniformly across roles (or be opt-in via local configuration).

For examples of production interceptors, see:

- `WorkerObservabilityInterceptor` and `WorkerMetricsInterceptor` in `common/worker-sdk/src/main/java/io/pockethive/worker/sdk/runtime`,
- `TemplatingInterceptor` in the same package for payload templating driven by worker config.
