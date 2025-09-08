# Postprocessor Service

Collects metrics from the final stage of the pipeline and emits aggregated results.

## Responsibilities
- Consume processed messages from `ph.<swarmId>.final`.
- Record latency and error metrics tagged with swarm and bee identifiers.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.
