# Processor Service

Calls the system under test and forwards responses downstream.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

The processor worker consumes the hive traffic exchange and queue bindings described in the active scenario
(`SwarmPlan.bees[*].work`). When you set `workers.processor.config` inside a scenario, those values are merged into the
plan and the Swarm Controller broadcasts them as `config-update` signals before the worker ever processes a message.
Local development can still populate `pockethive.inputs.rabbit` / `pockethive.outputs.rabbit`, but production swarms rely
exclusively on the scenario definition—environment overrides are ignored. The Worker SDK wires the Rabbit input/output
automatically, so no service-specific runtime adapter is required. Refer to the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) and the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for detailed property guidance.
