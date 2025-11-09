# Trigger Service

Executes scheduled side effects such as shell commands or HTTP calls.

See the [architecture reference](../docs/ARCHITECTURE.md) and [control-plane rules](../docs/rules/control-plane-rules.md) for signal and behaviour details.

## Configuration

Triggers also rely on the worker IO sections injected by the Swarm Controller. Define any required queues/exchanges under
`pockethive.inputs` / `pockethive.outputs` (for example `pockethive.outputs.rabbit.routing-key`) in `application.yml` or
environment variables. The [control-plane worker guide](../docs/control-plane/worker-guide.md#configuration-properties) and
[Worker SDK quick start](../docs/sdk/worker-sdk-quickstart.md) summarise the available options.
