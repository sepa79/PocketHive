package io.pockethive.e2e.support;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import io.pockethive.Topology;
import io.pockethive.control.Confirmation;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;

/**
 * Collects control-plane confirmations from RabbitMQ for later assertions.
 */
public final class ControlPlaneEvents implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneEvents.class);

  private final org.springframework.amqp.rabbit.connection.Connection connection;
  private final Channel channel;
  private final String queueName;
  private final String consumerTag;
  private final ControlPlaneEventParser parser;
  private final List<ConfirmationEnvelope> confirmations = new CopyOnWriteArrayList<>();

  public ControlPlaneEvents(ConnectionFactory connectionFactory) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    try {
      this.connection = connectionFactory.createConnection();
      this.channel = connection.createChannel(false);
      this.parser = new ControlPlaneEventParser();
      this.queueName = channel.queueDeclare("", false, true, true, Collections.emptyMap()).getQueue();
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.ready.#");
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.error.#");
      DeliverCallback callback = this::handleDelivery;
      this.consumerTag = channel.basicConsume(queueName, true, callback, consumerTag -> { });
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialise control-plane event consumer", ex);
    }
  }

  private void handleDelivery(String tag, Delivery delivery) {
    String routingKey = delivery.getEnvelope().getRoutingKey();
    byte[] body = delivery.getBody();
    try {
      Confirmation confirmation = parser.parse(routingKey, body);
      if (confirmation != null) {
        confirmations.add(new ConfirmationEnvelope(routingKey, confirmation, Instant.now()));
      } else {
        LOGGER.debug("Ignoring non-confirmation message on {}", routingKey);
      }
    } catch (IOException ex) {
      LOGGER.warn("Failed to parse confirmation payload on {}", routingKey, ex);
    }
  }

  public List<ConfirmationEnvelope> confirmations() {
    return new ArrayList<>(confirmations);
  }

  public Optional<ConfirmationEnvelope> findReady(String signal, String correlationId) {
    return confirmations.stream()
        .filter(env -> env.confirmation() instanceof ReadyConfirmation ready && matches(ready, signal, correlationId))
        .findFirst();
  }

  public Optional<ReadyConfirmation> readyConfirmation(String signal, String correlationId) {
    return findReady(signal, correlationId)
        .map(env -> (ReadyConfirmation) env.confirmation());
  }

  public List<ReadyConfirmation> readyConfirmations(String signal) {
    return confirmations.stream()
        .filter(env -> env.confirmation() instanceof ReadyConfirmation ready && matchesSignal(ready, signal))
        .map(env -> (ReadyConfirmation) env.confirmation())
        .toList();
  }

  public List<ErrorConfirmation> errors() {
    return confirmations.stream()
        .filter(env -> env.confirmation() instanceof ErrorConfirmation)
        .map(env -> (ErrorConfirmation) env.confirmation())
        .toList();
  }

  public List<ErrorConfirmation> errorsForCorrelation(String correlationId) {
    return confirmations.stream()
        .filter(env -> env.confirmation() instanceof ErrorConfirmation error
            && matchesCorrelation(error, correlationId))
        .map(env -> (ErrorConfirmation) env.confirmation())
        .toList();
  }

  public boolean hasEventOnRoutingKey(String routingKey) {
    return confirmations.stream().anyMatch(env -> Objects.equals(env.routingKey(), routingKey));
  }

  public long readyCount(String signal) {
    return confirmations.stream()
        .filter(env -> env.confirmation() instanceof ReadyConfirmation ready && matchesSignal(ready, signal))
        .count();
  }

  @Override
  public void close() {
    try {
      if (channel != null && channel.isOpen()) {
        if (consumerTag != null) {
          channel.basicCancel(consumerTag);
        }
        channel.close();
      }
    } catch (Exception ex) {
      LOGGER.debug("Failed to close control-plane channel", ex);
    }
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception ex) {
      LOGGER.debug("Failed to close control-plane connection", ex);
    }
  }

  private boolean matches(ReadyConfirmation ready, String signal, String correlationId) {
    return matchesSignal(ready, signal) && matchesCorrelation(ready, correlationId);
  }

  private boolean matchesSignal(ReadyConfirmation ready, String signal) {
    if (ready == null || signal == null) {
      return false;
    }
    return signal.equalsIgnoreCase(ready.signal());
  }

  private boolean matchesCorrelation(Confirmation confirmation, String correlationId) {
    if (confirmation == null || correlationId == null) {
      return false;
    }
    return correlationId.equals(confirmation.correlationId());
  }

  public record ConfirmationEnvelope(String routingKey, Confirmation confirmation, Instant receivedAt) {
  }
}
