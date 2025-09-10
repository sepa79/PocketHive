# Orchestrator Service

Acts as the Queen of the hive. It reads scenario plans and ensures each swarm is started, monitored and stopped at the right time.

## Responsibilities
- Load scenario definitions and expand them into swarm plans.
- Launch and stop service containers based on swarm templates.
- Create a Marshal for each swarm and hand off the relevant plan fragment.
- Publish swarm-level events on the control exchange and track swarm status.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.orchestrator.*` for runtime changes.
- Responds to `sig.status-request.orchestrator.*` with `ev.status-full` events.

## Docker
```bash
docker build -t orchestrator-service:latest .
docker run --rm orchestrator-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
