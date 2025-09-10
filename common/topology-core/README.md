# Topology Core

Shared constants for PocketHive's RabbitMQ topology.

## Constants

The `io.pockethive.Topology` class exposes the following names:

| Constant | Environment variable | Default |
| --- | --- | --- |
| `Topology.SWARM_ID` | `PH_SWARM_ID` | `default` |
| `Topology.EXCHANGE` | `PH_TRAFFIC_EXCHANGE` | `ph.<swarm>.hive` |
| `Topology.GEN_QUEUE` | `PH_GEN_QUEUE` | `ph.<swarm>.gen` |
| `Topology.MOD_QUEUE` | `PH_MOD_QUEUE` | `ph.<swarm>.mod` |
| `Topology.FINAL_QUEUE` | `PH_FINAL_QUEUE` | `ph.<swarm>.final` |
| `Topology.CONTROL_QUEUE` | `PH_CONTROL_QUEUE` | `ph.control` |
| `Topology.CONTROL_EXCHANGE` | `PH_CONTROL_EXCHANGE` | `ph.control` |

Each constant first checks an environment variable (or JVM system property) and falls back to the default value shown.

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
PH_SWARM_ID=beta PH_GEN_QUEUE=ph.beta.custom ./run-service
```

```bash
java -DPH_SWARM_ID=beta -DPH_GEN_QUEUE=ph.beta.custom ...
```

This allows services to join different swarms or adjust routing without code changes.
