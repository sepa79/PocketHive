# Postprocessor Service

Aggregates metrics from final responses and publishes them for analysis.

## Responsibilities
- Consume messages from `ph.<swarmId>.final`.
- Record hop and total latency metrics and error counts.
- Emit metric events to the control exchange.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.postprocessor.*` for runtime changes.
- Responds to `sig.status-request.postprocessor.*` with `ev.status-full` events.

## Docker
```bash
docker build -t postprocessor-service:latest .
docker run --rm postprocessor-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
