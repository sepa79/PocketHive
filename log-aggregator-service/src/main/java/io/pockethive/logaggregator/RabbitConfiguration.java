package io.pockethive.logaggregator;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Aligns RabbitMQ client limits with the large payloads produced while workers run in DEBUG mode.
 * The default 64 MiB ceiling was too restrictive once services started emitting verbose logs.
 */
@Configuration
class RabbitConfiguration {

  private final ConnectionFactory connectionFactory;
  private final int maxInboundBytes;

  RabbitConfiguration(
      ConnectionFactory connectionFactory,
      @Value("${pockethive.logs.maxInboundMessageBytes:104857600}") int maxInboundBytes) {
    this.connectionFactory = connectionFactory;
    this.maxInboundBytes = maxInboundBytes;
  }

  @PostConstruct
  void customize() {
    if (connectionFactory instanceof AbstractConnectionFactory abstractFactory) {
      abstractFactory.setMaxInboundMessageBodySize(maxInboundBytes);
    }
  }
}
