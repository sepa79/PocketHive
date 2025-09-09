# Swarm Controller Service

Marshal that manages a single swarm: provisions queues, launches bee containers and relays control signals.

## Responsibilities
- Expand swarm plans into concrete queue names.
- Launch and monitor bee containers.
- Route config signals to individual services and report status.

## Parameters
- `PH_SWARM_ID` – identifier for the swarm scope (default `default`).
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`).

## Signals
- Consumes `sig.config-update.swarm-controller.*` for runtime changes.
- Responds to `sig.status-request.swarm-controller.*` with `ev.status-full` events.

## Docker
```bash
docker build -t swarm-controller-service:latest .
docker run --rm swarm-controller-service:latest
```

See [control-plane rules](../docs/rules/control-plane-rules.md) for full signal formats.
