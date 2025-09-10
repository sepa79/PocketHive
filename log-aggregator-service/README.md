# Log Aggregator Service

Collects logs from services and ships them to Loki for storage.

## Responsibilities
- Consume log messages from `ph.logs`.
- Batch and forward logs to the Loki endpoint.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).
- `LOKI_URL` – destination Loki instance.

## Signals
- Consumes `sig.config-update.log-aggregator.*` for runtime changes.
- Responds to `sig.status-request.log-aggregator.*` with `ev.status-full` events.

## Docker
```bash
docker build -t log-aggregator-service:latest .
docker run --rm log-aggregator-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
