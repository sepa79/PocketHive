# Generator Service

Generates traffic for the swarm by publishing messages to the hive exchange.

## Responsibilities
- Build and send requests to `ph.<swarmId>.gen`.
- React to `config-update` and `status-request` signals on the control channel.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.generator.*` for runtime changes.
- Responds to `sig.status-request.generator.*` with `ev.status-full` events.

## Docker
```bash
docker build -t generator-service:latest .
docker run --rm generator-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
