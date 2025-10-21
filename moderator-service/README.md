# Moderator Service

Filters or rewrites generator output before it reaches the processor.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Moderator workers resolve their inbound/outbound queues from `pockethive.control-plane.queues.*`. Populate the queue
aliases and hive traffic exchange (`pockethive.control-plane.traffic-exchange`) in `application.yml` or the container
environment and review the
[control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) plus the
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) for examples.

