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

  /**
   * Returns the total number of messages currently buffered in the queue.
   *
   * @param queueName queue to inspect
   * @return message count (zero when the queue is empty)
   * @throws IllegalArgumentException if the queue does not exist
   */
  public long messageCount(String queueName) {
    Objects.requireNonNull(queueName, "queueName");
    Properties properties = rabbitAdmin.getQueueProperties(queueName);
    if (properties == null) {
      throw new IllegalArgumentException("Queue not found: " + queueName);
    }
    Object value = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    throw new IllegalStateException("Unsupported message count type " + value.getClass().getName()
        + " for queue " + queueName);
  }
}
