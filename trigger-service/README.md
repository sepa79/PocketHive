# Trigger Service

Executes side effects such as shell commands or HTTP calls on a schedule.

## Responsibilities
- Perform actions based on `ph.trigger.*` settings.
- React to control-plane signals for runtime configuration.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.trigger.*` for runtime changes.
- Responds to `sig.status-request.trigger.*` with `ev.status-full` events.

## Docker
```bash
docker build -t trigger-service:latest .
docker run --rm trigger-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
