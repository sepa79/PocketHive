# Processor Service

Calls the system under test with moderated messages and forwards responses.

## Responsibilities
- Consume messages from `ph.<swarmId>.mod`.
- Invoke the target system and publish results to `ph.<swarmId>.final`.
- React to control-plane signals for runtime configuration.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.processor.*` for runtime changes.
- Responds to `sig.status-request.processor.*` with `ev.status-full` events.

## Docker
```bash
docker build -t processor-service:latest .
docker run --rm processor-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
