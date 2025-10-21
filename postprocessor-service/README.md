# Postprocessor Service

Aggregates final responses and emits metrics for analysis.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Post-processor deployments resolve routing exclusively from `pockethive.control-plane.*` properties. Provide the hive
traffic exchange (`pockethive.control-plane.traffic-exchange`) and queue aliases (for example
`pockethive.control-plane.queues.final`) in configuration and consult the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for reference YAML.

