# PocketHive Control-Plane Worker & Manager Bootstrap Guide

This guide explains how to wire a new worker or manager service against the refactored control-plane APIs.
It covers the topology descriptors, emitters, and Spring Boot starters introduced in the refactor so teams can
bootstrap participants without copying configuration boilerplate. For an end-to-end walkthrough of the Stage 1–3
Worker SDK runtime, see the [Worker SDK quick start](../sdk/worker-sdk-quickstart.md).

## 1. Choose the right topology descriptor

Each PocketHive role ships with an immutable topology descriptor that defines queue names, bindings, and
supported routes. Resolve descriptors through `ControlPlaneTopologyDescriptorFactory` to avoid hard-coded
strings:

```java
ControlPlaneTopologySettings workerSettings = new ControlPlaneTopologySettings(
    "swarm-1",
    "ph.control",
    Map.of()
);
ControlPlaneTopologyDescriptor workerDescriptor =
    ControlPlaneTopologyDescriptorFactory.forWorkerRole("processor", workerSettings);
ControlPlaneTopologySettings managerSettings = new ControlPlaneTopologySettings(
    "swarm-1",
    "ph.control",
    Map.of()
);
ControlPlaneTopologyDescriptor managerDescriptor =
    ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator", managerSettings);
```

The descriptor validates role identifiers and exposes helpers for declaring the control queue and any
fan-out/status queues your service requires.

## 2. Emit control-plane traffic through `ControlPlaneEmitter`

`ControlPlaneEmitter` wraps routing conventions and payload factories. Build it with your descriptor and a
`ControlPlanePublisher` implementation (the Spring starter provides an AMQP publisher automatically):

```java
ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-1", workerDescriptor.role(), "processor-1");
ControlPlaneEmitter emitter = ControlPlaneEmitter.using(
    workerDescriptor,
    RoleContext.fromIdentity(identity),
    publisher
);
emitter.emitReady(ControlPlaneEmitter.ReadyContext.builder(
    "config-sync", correlationId, commandId, CommandState.status("Ready"))
    .result("ok")
    .build());
```

The emitter guarantees routing keys and payload schemas remain consistent across services.

## 3. Enable the Spring Boot starters and runtime

Include the Worker SDK starter to auto-register descriptors, identities, AMQP declarables, and the
control-plane publisher. The starter also exposes the Stage 1 `WorkerRuntime` and Stage 2
`WorkerControlPlaneRuntime` beans described in the quick start. The auto-configuration composes
`ControlPlaneCommonAutoConfiguration`, `WorkerControlPlaneAutoConfiguration`, and
`ManagerControlPlaneAutoConfiguration` so both worker and manager roles can be enabled from configuration.

```xml
<dependency>
  <groupId>io.pockethive</groupId>
  <artifactId>worker-sdk</artifactId>
</dependency>
```

### Configuration properties

`WorkerControlPlaneAutoConfiguration` binds a dedicated `WorkerControlPlaneProperties` bean. It fails fast when
any worker-facing control-plane keys are missing so misconfigurations surface at startup instead of being masked
by defaults. Populate the swarm identity, queue names, logging exchange, and Pushgateway contract explicitly:

```yaml
pockethive:
  control-plane:
    exchange: ph.control
    traffic-exchange: ph.swarm-1.hive
    swarm-id: swarm-1
    instance-id: processor-1
    queues:
      processor: ph.swarm-1.processor
      final: ph.swarm-1.final
    swarm-controller:
      rabbit:
        logs-exchange: ph.logs
        logging:
          enabled: true
      metrics:
        pushgateway:
          enabled: true
          base-url: http://pushgateway:9091
          push-rate: PT30S
          shutdown-operation: DELETE
    worker:
      role: processor
      skip-self-signals: false
    manager:
      enabled: false # disable if the service is worker-only
```

Workers and managers automatically inherit `pockethive.control-plane.swarm-id`
and `pockethive.control-plane.instance-id`. Participant-specific overrides are
no longer supported—set the shared properties once at the control-plane level.
The worker queues map can contain any role-specific bindings; the Swarm
Controller injects the same map into every worker container via environment
variables, and `WorkerControlPlaneProperties` enforces that each declared entry
is non-empty.

For a detailed breakdown of the Swarm Controller's environment contract, including every required `pockethive.control-plane.*` and RabbitMQ property, see the [Swarm Controller configuration reference](../../swarm-controller-service/README.md#configuration-reference).

### Runtime environment contract

Swarm-managed workers now boot solely from the environment variables injected by the Swarm Controller. Every
container **must** receive the following keys; missing values cause the entrypoint scripts and logging
configuration to abort fast:

| Category | Required variables |
| --- | --- |
| RabbitMQ connectivity | `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`, `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD`, `SPRING_RABBITMQ_VIRTUAL_HOST` |
| Control-plane identity & routing | `POCKETHIVE_CONTROL_PLANE_EXCHANGE`, `POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE`, `POCKETHIVE_CONTROL_PLANE_SWARM_ID`, `POCKETHIVE_CONTROL_PLANE_INSTANCE_ID`, `POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR`, `POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR`, `POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL`* |
| Logging contract | `POCKETHIVE_LOGS_EXCHANGE`, `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED`, `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE` |
| Metrics (when enabled) | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED`, `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL`, `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE`, `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION`, `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_ENABLED`, `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_BASE_URL`, `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_PUSH_RATE`, `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_JOB`, `MANAGEMENT_PROMETHEUS_METRICS_EXPORT_PUSHGATEWAY_GROUPING_KEY_INSTANCE` |

\*Moderator-only roles ignore `POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL`, and postprocessor-only roles ignore the
generator/moderator queues. The Swarm Controller still injects all queue names so multi-role deployments receive a
consistent contract.

Local overrides (e.g., docker-compose or Kubernetes manifests) must now provide the same keys when running workers
outside the Swarm Controller. A minimal docker-compose excerpt looks like this:

```yaml
services:
  generator:
    image: ghcr.io/pockethive/generator-service:latest
    environment:
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: "5672"
      SPRING_RABBITMQ_USERNAME: guest
      SPRING_RABBITMQ_PASSWORD: guest
      SPRING_RABBITMQ_VIRTUAL_HOST: "/"
      POCKETHIVE_CONTROL_PLANE_EXCHANGE: ph.control
      POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE: ph.dev.hive
      POCKETHIVE_CONTROL_PLANE_SWARM_ID: dev-swarm
      POCKETHIVE_CONTROL_PLANE_INSTANCE_ID: generator-dev
      POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR: ph.dev.gen
      POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR: ph.dev.mod
      POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL: ph.dev.final
      POCKETHIVE_LOGS_EXCHANGE: ph.logs
      POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED: "true"
      POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE: ph.logs
```

When metrics pushing is enabled, include the Pushgateway variables from the table above. Outside of controlled Swarm
deployments, keep these values in sync with the environment helper to avoid configuration drift.

With the starter in place, inject the beans exported by the auto-configuration:

```java
@Bean
CommandLineRunner controlPlaneRunner(ControlPlaneEmitter emitter, WorkerControlPlane workerControlPlane) {
    return args -> workerControlPlane.registerListener(signal -> {
        // handle inbound control signals
    });
}
```

Toggle `pockethive.control-plane.worker.enabled` or `.manager.enabled` to opt into the respective topology
wiring.

### Resetting worker overrides

The control plane keeps the most recent override for each worker bean until you clear it. To revert to the
boot-time defaults, emit a `config-update` signal that targets the worker with an explicit empty map. For
example:

```json
{
  "signal": "config-update",
  "args": {
    "workers": {
      "exampleWorker": {}
    }
  }
}
```

Because the payload singles out `exampleWorker` but provides no fields, the runtime interprets it as a reset
and drops any stored overrides. Ordinary enable/disable toggles that omit configuration continue to work as
before; only targeted empty maps trigger the reset behaviour.

## 4. Use the testing fixtures

The Worker SDK also provides `ControlPlaneTestFixtures` to simplify unit tests:

```java
WorkerControlPlaneProperties properties = ControlPlaneTestFixtures.workerProperties("swarm-1", "generator", "worker-a");
ControlPlaneEmitter emitter = ControlPlaneTestFixtures.workerEmitter(publisher, identity);
```

Fixtures construct canonical identities, descriptors, and ready-to-use emitters so tests can focus on
business logic rather than wiring.

## 5. Migration checklist

1. Add the `worker-sdk` dependency to your worker/manager service.
2. Rely on the RabbitMQ topology that the control-plane auto-configuration supplies—no bespoke queue declarations are required. **Do not declare the hive traffic exchange (`ph.{swarmId}.hive`) or its work queues**; the Swarm Controller is the sole owner of those bindings (see [Architecture §2.1](../ARCHITECTURE.md#21-managers) and the [AsyncAPI spec](../spec/asyncapi.yaml)).
3. Replace manual JSON payload builders with `ControlPlaneEmitter` helpers.
4. Configure `pockethive.control-plane.*` properties for your service roles.
5. Update unit tests to use `ControlPlaneTestFixtures` for canonical descriptors and identities.

With these steps your service aligns with the shared control-plane contract and benefits from centralised
configuration, routing, and payload generation.
