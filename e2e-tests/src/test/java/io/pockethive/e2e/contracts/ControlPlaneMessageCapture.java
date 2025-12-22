package io.pockethive.e2e.contracts;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

public final class ControlPlaneMessageCapture implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneMessageCapture.class);

  private final org.springframework.amqp.rabbit.connection.Connection connection;
  private final Channel channel;
  private final String queueName;
  private final String consumerTag;
  private final List<CapturedMessage> messages = new CopyOnWriteArrayList<>();

  public ControlPlaneMessageCapture(ConnectionFactory connectionFactory, String controlExchange) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    String exchange = requireNonBlank(controlExchange, "controlExchange");
    try {
      this.connection = connectionFactory.createConnection();
      this.channel = connection.createChannel(false);
      this.queueName = channel.queueDeclare("", false, true, true, Collections.emptyMap()).getQueue();
      channel.queueBind(queueName, exchange, "signal.#");
      channel.queueBind(queueName, exchange, "event.outcome.#");
      channel.queueBind(queueName, exchange, "event.alert.#");
      channel.queueBind(queueName, exchange, "event.metric.status-full.#");
      channel.queueBind(queueName, exchange, "event.metric.status-delta.#");
      DeliverCallback callback = this::handleDelivery;
      this.consumerTag = channel.basicConsume(queueName, true, callback, consumerTag -> { });
      LOGGER.info("Started control-plane capture queue={} exchange={}", queueName, exchange);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialise control-plane capture", ex);
    }
  }

  private void handleDelivery(String tag, Delivery delivery) {
    String routingKey = delivery.getEnvelope().getRoutingKey();
    byte[] body = delivery.getBody();
    messages.add(new CapturedMessage(routingKey, body, Instant.now()));
  }

  public List<CapturedMessage> messages() {
    return List.copyOf(messages);
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
      LOGGER.debug("Failed to close capture channel", ex);
    }
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception ex) {
      LOGGER.debug("Failed to close capture connection", ex);
    }
  }

  public record CapturedMessage(String routingKey, byte[] body, Instant receivedAt) {

    public CapturedMessage {
      routingKey = requireNonBlank(routingKey, "routingKey");
      body = body == null ? new byte[0] : body.clone();
      receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }

    public String payloadUtf8() {
      return new String(body, java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
