package io.pockethive.e2e.support;

import java.util.Objects;
import java.util.Properties;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * Lightweight helper to inspect queue existence without mutating broker state.
 */
public final class QueueProbe {

  private final RabbitAdmin rabbitAdmin;

  public QueueProbe(ConnectionFactory connectionFactory) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.rabbitAdmin = new RabbitAdmin(connectionFactory);
  }

  public boolean exists(String queueName) {
    Objects.requireNonNull(queueName, "queueName");
    Properties properties = rabbitAdmin.getQueueProperties(queueName);
    return properties != null;
  }
}
