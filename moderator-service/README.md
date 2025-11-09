# Moderator Service

Filters or rewrites generator output before it reaches the processor.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Moderator workers resolve their inbound/outbound queues from `pockethive.inputs.rabbit` /
`pockethive.outputs.rabbit`. Populate the queue/exchange/routing-key properties in `application.yml` (or via the
`POCKETHIVE_INPUT_RABBIT_QUEUE` / `POCKETHIVE_OUTPUT_RABBIT_*` environment variables) and review the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for examples.
