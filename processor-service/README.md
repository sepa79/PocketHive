# Processor Service

Calls the system under test and forwards responses downstream.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

The processor worker consumes the hive traffic exchange and queue bindings exposed via
`pockethive.inputs.rabbit` / `pockethive.outputs.rabbit`. Define the moderator queue, processor queue, and final routing
key under those sections (or via the `POCKETHIVE_INPUT_RABBIT_QUEUE` / `POCKETHIVE_OUTPUT_RABBIT_*` environment
variables). With
`pockethive.worker.inputs.autowire=true` (the default) the Worker SDK wires the Rabbit input/output automatically, so no
service-specific runtime adapter is required. Refer to the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) and the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for detailed property guidance.
