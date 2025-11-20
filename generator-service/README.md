# Generator Service

Generates swarm traffic by publishing messages to the hive exchange.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Generator routing is supplied by the worker IO sections. Configure the hive output exchange and routing key under
`pockethive.outputs.rabbit` (or via `POCKETHIVE_OUTPUT_RABBIT_*` environment variables); see the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) for the complete property
reference and [Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for sample YAML.

The generator also supports inline templating for its HTTP envelope. The `path`, `method`, `headers`, and `body`
fields are rendered through the shared `TemplateRenderer` (Pebble + constrained SpEL), with `payload`, `headers`,
and `workItem` available in the template context so you can randomise or derive values without extra code.
