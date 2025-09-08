# SwarmController Service

SwarmController manages the lifecycle of a single swarm. It consumes swarm-level signals and fans out
existing per-bee control messages without introducing new routing patterns.

## Responsibilities
- Listen for `sig.swarm-start.<swarmId>` and `sig.swarm-stop.<swarmId>` events.
- Respond on the control channel to `sig.status-request` and `sig.config-update` messages for the
  `swarm-controller` role.
- Resolve swarm membership and emit `sig.config-update.<role>.<instance>` or
  `sig.status-request.<role>.<instance>` messages for each bee.
- Tag all emitted metrics with the `swarm_id` label and expose a friendly bee name alongside a UUID.

## Build & Run

Build the Docker image from the repo root:

```sh
docker build -f swarm-controller-service/Dockerfile .
```

Run locally with Docker Compose (RabbitMQ must be running):

```sh
PH_SWARM_ID=swarm1 docker compose up swarm-controller
```

The container relies on `RABBITMQ_HOST`, `PH_CONTROL_EXCHANGE`, `PH_CONTROL_QUEUE`, and `PH_SWARM_ID` environment variables.

See [control-plane rules](../docs/rules/control-plane-rules.md) for canonical signal formats.
