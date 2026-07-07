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

## RabbitMQ

```
spring.rabbitmq.host             # RabbitMQ hostname shared across the control plane
spring.rabbitmq.port             # RabbitMQ port
spring.rabbitmq.username         # RabbitMQ username
spring.rabbitmq.password         # RabbitMQ password
spring.rabbitmq.virtual-host     # RabbitMQ virtual host
```

The Spring Boot `RabbitProperties` drive broker connectivity; the orchestrator
reuses those values when launching swarm controllers.

## Product metrics

```
pockethive.control-plane.orchestrator.metrics.adapter                         # CLICKHOUSE for active product metrics, DISABLED for explicit no-metrics targets
pockethive.control-plane.orchestrator.metrics.publish-interval                # ISO-8601 duration controlling publish frequency
pockethive.control-plane.orchestrator.metrics.clickhouse.endpoint             # ClickHouse HTTP endpoint
pockethive.control-plane.orchestrator.metrics.clickhouse.table                # Metrics table, normally ph_metrics_samples
pockethive.control-plane.orchestrator.metrics.clickhouse.username             # ClickHouse username, if required
pockethive.control-plane.orchestrator.metrics.clickhouse.password             # ClickHouse password, if required
pockethive.control-plane.orchestrator.metrics.clickhouse.connect-timeout-ms   # HTTP connect timeout
pockethive.control-plane.orchestrator.metrics.clickhouse.read-timeout-ms      # HTTP read timeout
pockethive.control-plane.orchestrator.metrics.clickhouse.batch-size           # Insert batch size
pockethive.control-plane.orchestrator.metrics.clickhouse.flush-interval-ms    # Max batch wait
pockethive.control-plane.orchestrator.metrics.clickhouse.max-buffered-samples # Bounded in-process buffer
pockethive.control-plane.orchestrator.metrics.clickhouse.max-label-count      # Label count limit
pockethive.control-plane.orchestrator.metrics.clickhouse.max-label-key-length # Label key length limit
pockethive.control-plane.orchestrator.metrics.clickhouse.max-label-value-length # Label value length limit
```

ClickHouse metrics settings are forwarded to swarm controllers and worker
containers through the shared control-plane environment factory. Use `DISABLED`
only for targets that intentionally do not publish metrics.

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
