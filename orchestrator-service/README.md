# Orchestrator Service

Acts as the Queen of the hive. It reads scenario plans and ensures each swarm is started, monitored and stopped at the right time.

## Responsibilities
- Load scenario definitions and expand them into swarm plans.
- Launch and stop service containers based on swarm templates.
- Create a Herald for each swarm and hand off the relevant plan fragment.
- Publish swarm-level events on the control exchange and track swarm status.

See [control-plane rules](../docs/rules/control-plane-rules.md) for signal formats.
