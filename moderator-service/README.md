# Moderator Service

Filters or rewrites generator output before it reaches the processor.

## Responsibilities
- Consume messages from `ph.<swarmId>.gen`.
- Optionally modify or drop messages before publishing to `ph.<swarmId>.mod`.
- React to control-plane signals for runtime configuration.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.moderator.*` for runtime changes.
- Responds to `sig.status-request.moderator.*` with `ev.status-full` events.

## Docker
```bash
docker build -t moderator-service:latest .
docker run --rm moderator-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
