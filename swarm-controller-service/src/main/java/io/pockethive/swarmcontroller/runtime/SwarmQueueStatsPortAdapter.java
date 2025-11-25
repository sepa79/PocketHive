package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.QueuePropertyCoercion;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Objects;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * Adapter that bridges {@link QueueStatsPort} to {@link AmqpAdmin}.
 */
public final class SwarmQueueStatsPortAdapter implements QueueStatsPort {

  private final AmqpAdmin amqpAdmin;

  public SwarmQueueStatsPortAdapter(AmqpAdmin amqpAdmin) {
    this.amqpAdmin = Objects.requireNonNull(amqpAdmin, "amqpAdmin");
  }

  @Override
  public QueueStats getQueueStats(String queueName) {
    Properties props = amqpAdmin.getQueueProperties(queueName);
    if (props == null) {
      return QueueStats.empty();
    }
    long depth = QueuePropertyCoercion.coerceLong(props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT));
    int consumers = QueuePropertyCoercion.coerceInt(props.get(RabbitAdmin.QUEUE_CONSUMER_COUNT));
    OptionalLong oldestAge = QueuePropertyCoercion.coerceOptionalLong(
        props.get("x-queue-oldest-age-seconds"));
    return new QueueStats(depth, consumers, oldestAge);
  }
}

