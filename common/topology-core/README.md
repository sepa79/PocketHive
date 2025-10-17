# Topology Core

Shared constants for PocketHive's RabbitMQ topology.

## Constants

The `io.pockethive.Topology` class exposes the following names and **requires** explicit configuration for each of them:

| Constant | Required environment or system property |
| --- | --- |
| `Topology.SWARM_ID` | `POCKETHIVE_CONTROL_PLANE_SWARM_ID` |
| `Topology.EXCHANGE` | `POCKETHIVE_TRAFFIC_EXCHANGE` |
| `Topology.GEN_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR` |
| `Topology.MOD_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR` |
| `Topology.FINAL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL` |
| `Topology.CONTROL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE` |
| `Topology.CONTROL_EXCHANGE` | `POCKETHIVE_CONTROL_PLANE_EXCHANGE` |

`Topology` fails fast with an `IllegalStateException` when any variable above is missing or blank. Use
`io.pockethive.TopologyDefaults` for compile-time constants (e.g., annotation attributes) when a literal value is required at
build time.

## Usage

```java
import io.pockethive.Topology;
import com.rabbitmq.client.Channel;

Channel channel = // obtain channel
channel.exchangeDeclare(Topology.EXCHANGE, "topic", true);
channel.queueDeclare(Topology.GEN_QUEUE, true, false, false, null);
```

All services rely on these constants to keep queue and exchange names consistent.

## Overrides

Supply environment variables or `-D` system properties at runtime to provide the required values:

```bash
POCKETHIVE_CONTROL_PLANE_SWARM_ID=beta POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR=ph.beta.custom ./run-service
```

```bash
java -DPOCKETHIVE_CONTROL_PLANE_SWARM_ID=beta -DPOCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR=ph.beta.custom ...
```

This allows services to join different swarms or adjust routing without code changes.
