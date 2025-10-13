package io.pockethive.e2e.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;

/**
 * Lightweight consumer used by the end-to-end tests to inspect workload queues without interfering
 * with application consumers. Instances operate with manual acknowledgements so callers can inspect
 * payloads before releasing them back to the broker.
 */
public final class WorkQueueConsumer implements AutoCloseable {

  private final org.springframework.amqp.rabbit.connection.Connection connection;
  private final Channel channel;
  private final String queueName;

  private WorkQueueConsumer(org.springframework.amqp.rabbit.connection.Connection connection,
                            Channel channel,
                            String queueName) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.queueName = Objects.requireNonNull(queueName, "queueName");
  }

  public WorkQueueConsumer(ConnectionFactory connectionFactory, String queueName) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    Objects.requireNonNull(queueName, "queueName");
    if (queueName.isBlank()) {
      throw new IllegalArgumentException("queueName must not be blank");
    }
    org.springframework.amqp.rabbit.connection.Connection newConnection = null;
    Channel newChannel = null;
    try {
      newConnection = connectionFactory.createConnection();
      newChannel = newConnection.createChannel(false);
      this.connection = newConnection;
      this.channel = newChannel;
      this.queueName = queueName;
    } catch (Exception ex) {
      closeQuietly(newChannel);
      closeQuietly(newConnection);
      throw new IllegalStateException("Failed to initialise work queue consumer for " + queueName, ex);
    }
  }

  public static WorkQueueConsumer forExchangeTap(ConnectionFactory connectionFactory,
                                                 String exchange,
                                                 String routingKey) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    Objects.requireNonNull(exchange, "exchange");
    Objects.requireNonNull(routingKey, "routingKey");
    org.springframework.amqp.rabbit.connection.Connection connection = null;
    Channel channel = null;
    try {
      connection = connectionFactory.createConnection();
      channel = connection.createChannel(false);
      String queueName = channel.queueDeclare("", false, true, true, Map.of()).getQueue();
      channel.queueBind(queueName, exchange, routingKey);
      return new WorkQueueConsumer(connection, channel, queueName);
    } catch (Exception ex) {
      closeQuietly(channel);
      closeQuietly(connection);
      throw new IllegalStateException(
          "Failed to initialise exchange tap for exchange=" + exchange + " routingKey=" + routingKey, ex);
    }
  }

  public String queueName() {
    return queueName;
  }

  public Optional<Message> basicGet() {
    try {
      GetResponse response = channel.basicGet(queueName, false);
      if (response == null) {
        return Optional.empty();
      }
      return Optional.of(from(response));
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to basicGet from queue " + queueName, ex);
    }
  }

  public Optional<Message> consumeNext(Duration timeout) {
    Objects.requireNonNull(timeout, "timeout");
    BlockingQueue<Message> buffer = new ArrayBlockingQueue<>(1);
    DeliverCallback callback = (tag, delivery) -> buffer.offer(from(delivery));
    String consumerTag = null;
    try {
      consumerTag = channel.basicConsume(queueName, false, callback, consumer -> { });
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to start consumer for queue " + queueName, ex);
    }
    try {
      Message message = buffer.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return Optional.ofNullable(message);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for message on queue " + queueName, ex);
    } finally {
      try {
        if (consumerTag != null) {
          channel.basicCancel(consumerTag);
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to cancel consumer for queue " + queueName, ex);
      }
    }
  }

  private Message from(GetResponse response) {
    long tag = response.getEnvelope().getDeliveryTag();
    String routingKey = response.getEnvelope().getRoutingKey();
    byte[] body = response.getBody();
    Map<String, Object> headers = normaliseHeaders(response.getProps());
    return new Message(channel, queueName, tag, routingKey, body, headers);
  }

  private Message from(Delivery delivery) {
    long tag = delivery.getEnvelope().getDeliveryTag();
    String routingKey = delivery.getEnvelope().getRoutingKey();
    byte[] body = delivery.getBody();
    Map<String, Object> headers = normaliseHeaders(delivery.getProperties());
    return new Message(channel, queueName, tag, routingKey, body, headers);
  }

  private Map<String, Object> normaliseHeaders(AMQP.BasicProperties properties) {
    if (properties == null) {
      return Map.of();
    }
    Map<String, Object> raw = properties.getHeaders();
    Map<String, Object> headers = new LinkedHashMap<>();
    if (raw != null && !raw.isEmpty()) {
      raw.forEach((key, value) -> {
        if (key != null) {
          headers.put(key, normaliseValue(value));
        }
      });
    }
    if (properties.getMessageId() != null && !headers.containsKey("message-id")) {
      headers.put("message-id", properties.getMessageId());
    }
    if (properties.getContentType() != null && !headers.containsKey("content-type")) {
      headers.put("content-type", properties.getContentType());
    }
    return headers.isEmpty() ? Map.of() : Collections.unmodifiableMap(headers);
  }

  private static Object normaliseValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof LongString longString) {
      return longString.toString();
    }
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    if (value instanceof List<?> list) {
      List<Object> converted = new ArrayList<>(list.size());
      for (Object element : list) {
        converted.add(normaliseValue(element));
      }
      return Collections.unmodifiableList(converted);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> converted = new LinkedHashMap<>();
      map.forEach((key, val) -> {
        if (key != null) {
          converted.put(key.toString(), normaliseValue(val));
        }
      });
      return Collections.unmodifiableMap(converted);
    }
    return value;
  }

  @Override
  public void close() {
    try {
      if (channel != null && channel.isOpen()) {
        channel.close();
      }
    } catch (Exception ex) {
      // ignore cleanup failures
    }
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception ex) {
      // ignore cleanup failures
    }
  }

  private static void closeQuietly(Channel channel) {
    if (channel == null) {
      return;
    }
    try {
      if (channel.isOpen()) {
        channel.close();
      }
    } catch (Exception ex) {
      // ignore cleanup failures
    }
  }

  private static void closeQuietly(org.springframework.amqp.rabbit.connection.Connection connection) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (Exception ex) {
      // ignore cleanup failures
    }
  }

  public static final class Message {

    private final Channel channel;
    private final String queueName;
    private final long deliveryTag;
    private final String routingKey;
    private final byte[] body;
    private final Map<String, Object> headers;
    private final AtomicBoolean acknowledged = new AtomicBoolean(false);

    Message(Channel channel,
            String queueName,
            long deliveryTag,
            String routingKey,
            byte[] body,
            Map<String, Object> headers) {
      this.channel = channel;
      this.queueName = queueName;
      this.deliveryTag = deliveryTag;
      this.routingKey = routingKey;
      this.body = body == null ? new byte[0] : body.clone();
      this.headers = headers == null ? Map.of() : headers;
    }

    public String routingKey() {
      return routingKey;
    }

    public Map<String, Object> headers() {
      return headers;
    }

    public byte[] body() {
      return body.clone();
    }

    public String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }

    public void ack() {
      if (!acknowledged.compareAndSet(false, true)) {
        return;
      }
      try {
        channel.basicAck(deliveryTag, false);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to ack message on queue " + queueName, ex);
      }
    }

    public void nack(boolean requeue) {
      if (!acknowledged.compareAndSet(false, true)) {
        return;
      }
      try {
        channel.basicNack(deliveryTag, false, requeue);
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to nack message on queue " + queueName, ex);
      }
    }
  }
}
