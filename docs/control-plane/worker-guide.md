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
ControlPlaneTopologyDescriptor workerDescriptor =
    ControlPlaneTopologyDescriptorFactory.forWorkerRole("processor");
ControlPlaneTopologyDescriptor managerDescriptor =
    ControlPlaneTopologyDescriptorFactory.forManagerRole("orchestrator");
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

Configure minimal properties for each role:

```yaml
pockethive:
  control-plane:
    exchange: ph.control
    swarm-id: swarm-1
    worker:
      role: processor
      instance-id: processor-1
    manager:
      enabled: false # disable if the service is worker-only
```

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
ControlPlaneProperties properties = ControlPlaneTestFixtures.workerProperties("swarm-1", "generator", "worker-a");
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
