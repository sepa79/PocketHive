# Scenario Manager Service

REST service for managing simulation scenarios. Stores scenarios in memory and persists them as JSON or YAML files.

Each request is logged and published to the `PH_LOGS_EXCHANGE` (default `ph.logs`) for collection by Buzz.

## Parameters
- `RABBITMQ_HOST` – broker hostname (default `rabbitmq`)
- `RABBITMQ_PORT` – broker port (default `5672`)
- `RABBITMQ_USER` – broker username (default `guest`)
- `RABBITMQ_PASS` – broker password (default `guest`)
- `PH_LOGS_QUEUE` – queue to wait for before starting (default `ph.logs.agg`)
