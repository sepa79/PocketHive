# PocketHive Control-Plane Spring Support

This module provides Spring Boot auto-configuration for the PocketHive control plane. It exposes
beans that translate the descriptor model defined in `common/control-plane-core` into
Spring AMQP `Queue`, `Binding`, and `TopicExchange` instances, alongside publisher and
listener infrastructure tuned for worker and manager services.

## Features

- Resolves `ControlPlaneTopologyDescriptor` implementations for common PocketHive roles.
- Declares control-plane queues and bindings derived from descriptors via Spring AMQP
  `Declarables`.
- Registers an `AmqpControlPlanePublisher` bound to the configured control exchange.
- Configures `WorkerControlPlane` and `ManagerControlPlane` helpers with duplicate detection
  and self-filtering capabilities.
- Exposes configuration properties under the `pockethive.control-plane` prefix with toggles
  to enable/disable publishers, listeners, and topology declarations per participant.

## Key properties

```yaml
pockethive:
  control-plane:
    exchange: ph.control                 # Control exchange (must be provided explicitly)
    control-queue-prefix: ph.control     # Base prefix used for control queue names
    swarm-id: default                    # Swarm identifier consumed by descriptors
    publisher:
      enabled: true                      # Publish control-plane traffic via AMQP
    worker:
      enabled: true
      role: generator                    # Resolves the worker descriptor
      instance-id: gen-1                 # Instance identifier used for queues and self-filtering
      listener:
        enabled: true                    # Expose WorkerControlPlane bean
      duplicate-cache:
        ttl: 5m
        capacity: 1024
    manager:
      enabled: true
      role: orchestrator
      instance-id: orch-1
      listener:
        enabled: true
      duplicate-cache:
        ttl: 5m
        capacity: 1024
```

See `META-INF/additional-spring-configuration-metadata.json` for the complete list of
available properties.

## Usage

Add the module to a service and rely on standard Spring Boot auto-configuration:

```xml
<dependency>
  <groupId>io.pockethive</groupId>
  <artifactId>control-plane-spring</artifactId>
  <version>${project.version}</version>
</dependency>
```

Provide the relevant properties (for example via `application.yml`) and the module will
expose ready-to-use beans:

- `TopicExchange` named `controlPlaneExchange`
- `Declarables` containing worker/manager queues and bindings
- `ControlPlanePublisher` backed by the provided `AmqpTemplate`
- `WorkerControlPlane` or `ManagerControlPlane` beans depending on the configured role

These beans can be injected into existing components, reducing the bespoke RabbitMQ setup
previously repeated across services.
