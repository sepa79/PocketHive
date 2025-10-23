# Orchestrator Configuration Contract

The orchestrator service sources all runtime settings from explicit Spring Boot
configuration. Every property listed below must be supplied via
`application.yml`, environment variables, or the command line. Missing values
cause startup to fail fast.

## Control-plane identity

```
pockethive.control-plane.instance-id     # Required unique identifier for the orchestrator instance
pockethive.control-plane.swarm-id        # Logical swarm segment managed by the orchestrator
pockethive.control-plane.manager.role    # Control-plane role (defaults to `orchestrator`)
pockethive.control-plane.exchange        # Control-plane topic exchange name
```

These properties are consumed directly by the shared `ControlPlaneProperties`
bean. Set them explicitly for each orchestrator deployment to avoid relying on
legacy defaults.

## Orchestrator control-plane bindings

```
pockethive.control-plane.orchestrator.control-queue-prefix  # Prefix for the orchestrator control queue (suffixes with instance id)
pockethive.control-plane.orchestrator.status-queue-prefix   # Prefix for the orchestrator status queue (suffixes with instance id)
```

The orchestrator composes queue names using these prefixes and the runtime
instance id.

## RabbitMQ and logging

```
spring.rabbitmq.host             # RabbitMQ hostname shared across the control plane
spring.rabbitmq.port             # RabbitMQ port
spring.rabbitmq.username         # RabbitMQ username
spring.rabbitmq.password         # RabbitMQ password
spring.rabbitmq.virtual-host     # RabbitMQ virtual host
pockethive.control-plane.orchestrator.rabbit.logs-exchange   # Exchange used by the logback AMQP appender
pockethive.control-plane.orchestrator.rabbit.logging.enabled # Toggle for logback AMQP appender (boolean)
```

The Spring Boot `RabbitProperties` drive broker connectivity; the orchestrator
reuses those values when launching swarm controllers. Disable the log appender
by setting `pockethive.control-plane.orchestrator.rabbit.logging.enabled=false`.

## Metrics pushgateway

```
pockethive.control-plane.orchestrator.metrics.pushgateway.enabled              # Boolean toggle for worker/exporter pushgateway integration
pockethive.control-plane.orchestrator.metrics.pushgateway.base-url             # Pushgateway base URL
pockethive.control-plane.orchestrator.metrics.pushgateway.push-rate            # ISO-8601 duration controlling push frequency
pockethive.control-plane.orchestrator.metrics.pushgateway.shutdown-operation   # Pushgateway shutdown behavior
pockethive.control-plane.orchestrator.metrics.pushgateway.job                  # Job label forwarded to workers
pockethive.control-plane.orchestrator.metrics.pushgateway.grouping-key.instance # Grouping key instance label
```

All pushgateway values are forwarded verbatim to swarm controllers and worker
containers. Omit the section only if metrics are entirely disabled for the
deployment.

## Docker

```
pockethive.control-plane.orchestrator.docker.socket-path    # Path to the Docker socket mounted into swarm controllers
```

The socket path is injected into controller containers and determines the bind
mount used during startup.

## Scenario manager client

```
pockethive.control-plane.orchestrator.scenario-manager.url                # Base URL for the scenario-manager service
pockethive.control-plane.orchestrator.scenario-manager.http.connect-timeout  # ISO-8601 duration (e.g. PT5S)
pockethive.control-plane.orchestrator.scenario-manager.http.read-timeout     # ISO-8601 duration (e.g. PT30S)
```

The HTTP client fails fast when the URL is omitted or blank. Adjust the
timeouts to align with your deployment.

Consult `orchestrator-service/src/main/resources/application.yml` for the
baseline development values and their environment variable overrides.
