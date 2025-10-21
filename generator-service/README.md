# Generator Service

Generates swarm traffic by publishing messages to the hive exchange.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Generator routing is supplied by `pockethive.control-plane.*` properties. Configure the hive exchange and queue aliases
(`pockethive.control-plane.queues.generator`, etc.) in `application.yml` or environment variables; see the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) for the complete property
reference and [Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for sample YAML.

