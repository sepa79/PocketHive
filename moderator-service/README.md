# Moderator Service

Filters or rewrites generator output before it reaches the processor.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Moderator workers resolve their inbound/outbound queues from the swarm plan (`SwarmPlan.bees[*].work`) and receive
per-role overrides from the scenario’s `workers.moderator.config` block. When a swarm starts, the controller injects
those plan values via control-plane `config-update` signals, so there is no need to duplicate them in environment
variables. Service defaults declared under `pockethive.inputs/outputs.*` remain helpful for local runs, but once the
controller launches a swarm the scenario configuration is the single source of truth. Review the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for examples.
