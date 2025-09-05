# RabbitMQ Logging

Services ship JSON-formatted log events to a RabbitMQ exchange using the
off-the-shelf `AmqpAppender` provided by `spring-rabbit` (via
`spring-boot-starter-amqp`) combined with
`net.logstash.logback:logstash-logback-encoder`.

## Configuration

The appender reads its connection settings from the following keys (environment
variables shown in parentheses):

| Key | Env Var | Default | Description |
|-----|---------|---------|-------------|
| `rabbitmq.host` | `RABBITMQ_HOST` | `localhost` | RabbitMQ host to connect to. |
| `rabbitmq.port` | `RABBITMQ_PORT` | `5672` | RabbitMQ port. |
| `rabbitmq.username` | `RABBITMQ_DEFAULT_USER` | `guest` | Username for the RabbitMQ connection. |
| `rabbitmq.password` | `RABBITMQ_DEFAULT_PASS` | `guest` | Password for the RabbitMQ connection. |
| `rabbitmq.vhost` | `RABBITMQ_VHOST` | `/` | Virtual host for the connection. |
| `logs.exchange` | `LOGS_EXCHANGE` | `ph.logs` | Exchange where log events are published. |
| `rabbitmq.logging.enabled` | `RABBITMQ_LOGGING_ENABLED` | `true` | Any value other than `false` enables publishing; set to `false` to disable (useful locally). |

## Usage

Ensure `spring-boot-starter-amqp` is on the classpath, add the encoder
dependency, and register the appender in your service's `logback.xml`:

```xml
<if condition='!"false".equalsIgnoreCase(property("RABBITMQ_LOGGING_ENABLED"))'>
  <then>
    <appender name="RABBIT" class="org.springframework.amqp.rabbit.logback.AmqpAppender">
      <host>${RABBITMQ_HOST:-localhost}</host>
      <port>${RABBITMQ_PORT:-5672}</port>
      <username>${RABBITMQ_DEFAULT_USER:-guest}</username>
      <password>${RABBITMQ_DEFAULT_PASS:-guest}</password>
      <virtualHost>${RABBITMQ_VHOST:-/}</virtualHost>
      <exchangeName>${LOGS_EXCHANGE:-ph.logs}</exchangeName>
      <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
      <appender-ref ref="RABBIT"/>
    </root>
  </then>
  <else>
    <root level="INFO">
      <appender-ref ref="CONSOLE"/>
    </root>
  </else>
</if>
```

Each log event is serialized as JSON and published to the configured exchange.

### Avoiding logging loops

AMQP client libraries are noisy and, if routed through the `AmqpAppender`,
would generate recursive log traffic. Define dedicated loggers that send those
categories only to the console:

```xml
<logger name="org.springframework.amqp" level="WARN" additivity="false">
  <appender-ref ref="CONSOLE"/>
</logger>
<logger name="com.rabbitmq" level="WARN" additivity="false">
  <appender-ref ref="CONSOLE"/>
</logger>
```
