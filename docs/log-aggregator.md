# Log Aggregator

The `log-aggregator` service consumes log events from RabbitMQ and forwards them to Loki using its HTTP API.

## Configuration

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `PH_LOGS_EXCHANGE`  | RabbitMQ exchange to bind for log messages. | `ph.logs` |
| `PH_LOGS_QUEUE`     | Queue name used by the aggregator. | `ph.logs.agg` |
| `PH_LOKI_URL`       | Base URL for Loki ("/loki/api/v1/push" is appended). | `http://loki:3100` |
| `PH_LOKI_MAX_RETRIES` | Number of attempts for pushing a batch. | `3` |
| `PH_LOKI_BACKOFF_MS` | Initial backoff in milliseconds between retries. | `500` |
| `PH_LOKI_BATCH_SIZE` | Maximum number of log entries per batch. | `100` |
| `PH_LOKI_FLUSH_INTERVAL_MS` | Flush interval for sending batches. | `1000` |

The service batches incoming messages and sends them to Loki with labels for `service` and `traceId`.

The `ph.logs` exchange and `ph.logs.agg` queue are provisioned separately (see `rabbitmq/definitions.json`); the aggregator does not create them at runtime.

## Running

Add the service to your Compose file and provide the RabbitMQ and Loki connection settings.
