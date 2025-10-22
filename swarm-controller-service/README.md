# Swarm Controller Service

Manages a single swarm by provisioning queues, launching bees, and relaying control signals.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details. Operators looking for the configuration hand-off from the Orchestrator should use the reference below.

## Configuration reference

The controller is fully configured through environment-backed Spring properties. Every property below is surfaced without a fallback so misconfigured swarms fail fast when the Orchestrator launches a controller instance.

| Property | Required? | Purpose | Runtime env variable |
| --- | --- | --- | --- |
| `spring.rabbitmq.host` | Yes | RabbitMQ host that owns the control-plane exchanges. | `SPRING_RABBITMQ_HOST` |
| `spring.rabbitmq.port` | Yes | RabbitMQ port exposed to the controller. | `SPRING_RABBITMQ_PORT` |
| `spring.rabbitmq.username` | Yes | RabbitMQ username used for declaring control-plane topology. | `SPRING_RABBITMQ_USERNAME` |
| `spring.rabbitmq.password` | Yes | RabbitMQ password paired with the username above. | `SPRING_RABBITMQ_PASSWORD` |
| `pockethive.control-plane.exchange` | Yes | Primary control-plane exchange (e.g. `ph.control`). | `POCKETHIVE_CONTROL_PLANE_EXCHANGE` |
| `pockethive.control-plane.swarm-id` | Yes | Swarm identifier used for hive routing and metrics grouping. | `POCKETHIVE_CONTROL_PLANE_SWARM_ID` |
| `pockethive.control-plane.instance-id` | Yes | Unique controller instance id; doubles as the control queue suffix. | `POCKETHIVE_CONTROL_PLANE_INSTANCE_ID` |
| `pockethive.control-plane.worker.enabled` | Yes | Keeps the worker-side auto-configuration disabled for the controller JVM. | `POCKETHIVE_CONTROL_PLANE_WORKER_ENABLED` |
| `pockethive.control-plane.manager.role` | Yes | Declares the manager role (`swarm-controller`) for routing and logging context. | `POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE` |
| `pockethive.control-plane.swarm-controller.control-queue-prefix` | Yes | Prefix for controller-specific control queues. | `POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX` |
| `pockethive.control-plane.swarm-controller.traffic.queue-prefix` | Yes | Base prefix for hive work queues that workers bind to. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX` |
| `pockethive.control-plane.swarm-controller.traffic.hive-exchange` | Yes | Hive traffic exchange declared for swarm work fan-out. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE` |
| `pockethive.control-plane.swarm-controller.rabbit.logs-exchange` | Yes | Exchange that receives aggregated bee logs. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE` |
| `pockethive.control-plane.swarm-controller.rabbit.logging.enabled` | Yes | Enables or disables log shipping through the logs exchange. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED` |
| `pockethive.control-plane.swarm-controller.metrics.pushgateway.enabled` | Yes | Flags whether Pushgateway publishing is active. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED` |
| `pockethive.control-plane.swarm-controller.metrics.pushgateway.base-url` | No | Optional Pushgateway endpoint propagated into bee containers. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_BASE_URL` |
| `pockethive.control-plane.swarm-controller.metrics.pushgateway.push-rate` | Yes | Interval (ISO-8601 duration) between Pushgateway scrapes. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE` |
| `pockethive.control-plane.swarm-controller.metrics.pushgateway.shutdown-operation` | Yes | HTTP verb emitted when a bee shuts down (e.g. `DELETE`). | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION` |
| `pockethive.control-plane.swarm-controller.docker.socket-path` | Yes | Docker socket path mounted into the controller container. | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH` |
| `pockethive.control-plane.swarm-controller.docker.host` | No | Overrides the derived `DOCKER_HOST` (useful for TCP daemons). | `POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_HOST` |
| `logging.config` | No | Points to the Logback XML used at runtime. Defaults to `classpath:logback-spring.xml`. | `LOGGING_CONFIG` |
| `logging.pattern.level` | No | Overrides the log level pattern (default `%5p [%X{traceId}]`). | `LOGGING_PATTERN_LEVEL` |

### Example Orchestrator ➝ Swarm Controller hand-off

When the Orchestrator spins up a controller it injects raw environment references—no defaults—so a missing value fails fast during bootstrap. A minimal `application.yml` fragment looks like:

```yaml
pockethive:
  control-plane:
    exchange: ${POCKETHIVE_CONTROL_PLANE_EXCHANGE}
    swarm-id: ${POCKETHIVE_CONTROL_PLANE_SWARM_ID}
    instance-id: ${POCKETHIVE_CONTROL_PLANE_INSTANCE_ID}
    manager:
      role: ${POCKETHIVE_CONTROL_PLANE_MANAGER_ROLE}
    swarm-controller:
      control-queue-prefix: ${POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE_PREFIX}
      traffic:
        queue-prefix: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX}
        hive-exchange: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE}
      rabbit:
        logs-exchange: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGS_EXCHANGE}
        logging:
          enabled: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_RABBIT_LOGGING_ENABLED}
      metrics:
        pushgateway:
          enabled: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_ENABLED}
          push-rate: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_PUSH_RATE}
          shutdown-operation: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_METRICS_PUSHGATEWAY_SHUTDOWN_OPERATION}
      docker:
        socket-path: ${POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_DOCKER_SOCKET_PATH}
```

Use the table above to supply the matching environment variables before scheduling the controller container.

