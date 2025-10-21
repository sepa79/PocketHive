# Processor Service

Calls the system under test and forwards responses downstream.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

The processor runtime adapter consumes the hive exchange and queue aliases exposed via
`pockethive.control-plane.*`. Define `pockethive.control-plane.queues.moderator`, `.processor`, and `.final` alongside the
exchange in `application.yml` or environment variables. Refer to the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) and the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for detailed property guidance.

