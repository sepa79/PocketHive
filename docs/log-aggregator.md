# Log Aggregator

The `log-aggregator` service consumes log events from RabbitMQ and forwards them to Loki using its HTTP API.

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `POCKETHIVE_LOGS_EXCHANGE`  | RabbitMQ exchange to bind for log messages. | `ph.logs` |
| `POCKETHIVE_LOGS_QUEUE`     | Queue name used by the aggregator. | `ph.logs.agg` |
| `POCKETHIVE_LOKI_URL`       | Base URL for Loki ("/loki/api/v1/push" is appended). | `http://loki:3100` |
| `POCKETHIVE_LOKI_MAX_RETRIES` | Number of attempts for pushing a batch. | `3` |
| `POCKETHIVE_LOKI_BACKOFF_MS` | Initial backoff in milliseconds between retries. | `500` |
| `POCKETHIVE_LOKI_BATCH_SIZE` | Maximum number of log entries per batch. | `100` |
| `POCKETHIVE_LOKI_FLUSH_INTERVAL_MS` | Flush interval for sending batches. | `1000` |

The service batches incoming messages and sends them to Loki with labels for `service` and `traceId`.

The `ph.logs` exchange and `ph.logs.agg` queue are provisioned separately (see `rabbitmq/definitions.json`); the aggregator does not create them at runtime.

At startup the container polls RabbitMQ's management API until the `POCKETHIVE_LOGS_QUEUE` exists, ensuring the broker has loaded its definitions before the application begins consuming messages.

When running under `docker-compose`, the service's health check now allows up to five minutes for this startup phase so dependent containers only begin once the aggregator is fully ready.

## Running

Add the service to your Compose file and provide the RabbitMQ and Loki connection settings.
