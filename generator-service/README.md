# Generator Service

Generates traffic for the swarm by publishing messages to the hive exchange.

## Responsibilities
- Build and send requests to `ph.<swarmId>.gen`.
- React to `config-update` and `status-request` signals on the control channel.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.
