# Orchestrator Service

Acts as the queen, managing swarm containers and their lifecycle.

## Responsibilities
- Launch and stop service containers based on swarm templates.
- Publish swarm-level events on the control exchange.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.
