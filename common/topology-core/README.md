# Topology Core

Shared constants for PocketHive's RabbitMQ topology.

## Constants

The `io.pockethive.Topology` class exposes the following names:

| Constant | Environment variable | Default |
| --- | --- | --- |
| `Topology.SWARM_ID` | `POCKETHIVE_CONTROL_PLANE_SWARM_ID` | `default` |
| `Topology.EXCHANGE` | `POCKETHIVE_TRAFFIC_EXCHANGE` | `ph.<swarm>.hive` |
| `Topology.GEN_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR` | `ph.<swarm>.gen` |
| `Topology.MOD_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR` | `ph.<swarm>.mod` |
| `Topology.FINAL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL` | `ph.<swarm>.final` |
| `Topology.CONTROL_QUEUE` | `POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE` | `ph.control` |
| `Topology.CONTROL_EXCHANGE` | `POCKETHIVE_CONTROL_PLANE_EXCHANGE` | `ph.control` |

Each constant first checks the environment variable (or JVM system property) and falls back to the default value shown.

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

Supply environment variables or `-D` system properties at runtime to override defaults:

```bash
POCKETHIVE_CONTROL_PLANE_SWARM_ID=beta POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR=ph.beta.custom ./run-service
```

```bash
java -DPOCKETHIVE_CONTROL_PLANE_SWARM_ID=beta -DPOCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR=ph.beta.custom ...
```

This allows services to join different swarms or adjust routing without code changes.
