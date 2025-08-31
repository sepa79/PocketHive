# RabbitMQ Logging

The `rabbitmq-logging` module provides a Logback appender that publishes JSON log events to a RabbitMQ exchange.

## Configuration

The appender reads its connection settings from the following keys (environment variables shown in parentheses):

| Key | Env Var | Default | Description |
|-----|---------|---------|-------------|
| `rabbitmq.host` | `RABBITMQ_HOST` | `localhost` | RabbitMQ host to connect to. |
| `rabbitmq.username` | `RABBITMQ_DEFAULT_USER` | `guest` | Username for the RabbitMQ connection. |
| `rabbitmq.password` | `RABBITMQ_DEFAULT_PASS` | `guest` | Password for the RabbitMQ connection. |
| `logs.exchange` | `LOGS_EXCHANGE` | `logs.exchange` | Exchange where log events are published. |
| `rabbitmq.logging.enabled` | `RABBITMQ_LOGGING_ENABLED` | `true` | Set to `false` to disable publishing (useful locally). |

## Usage

Add the `rabbitmq-logging` module as a dependency and register the appender in your service's `logback.xml`:

```xml
<appender name="RABBIT" class="io.pockethive.logging.RabbitMqLogAppender">
  <host>${RABBITMQ_HOST:-localhost}</host>
  <username>${RABBITMQ_DEFAULT_USER:-guest}</username>
  <password>${RABBITMQ_DEFAULT_PASS:-guest}</password>
  <exchange>${LOGS_EXCHANGE:-logs.exchange}</exchange>
  <enabled>${RABBITMQ_LOGGING_ENABLED:-true}</enabled>
</appender>
```

Each log event is serialized as JSON and published to the configured exchange.
