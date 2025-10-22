package io.pockethive.e2e.support;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
  private final List<StatusEnvelope> statuses = new CopyOnWriteArrayList<>();
  private final String controlExchange;

  public ControlPlaneEvents(ConnectionFactory connectionFactory, String controlExchange) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    this.controlExchange = requireExchange(controlExchange);
    try {
      this.connection = connectionFactory.createConnection();
      this.channel = connection.createChannel(false);
      this.parser = new ControlPlaneEventParser();
      this.queueName = channel.queueDeclare("", false, true, true, Collections.emptyMap()).getQueue();
      channel.queueBind(queueName, this.controlExchange, "ev.ready.#");
      channel.queueBind(queueName, this.controlExchange, "ev.error.#");
      channel.queueBind(queueName, this.controlExchange, "ev.status-full.#");
      channel.queueBind(queueName, this.controlExchange, "ev.status-delta.#");
      DeliverCallback callback = this::handleDelivery;
      this.consumerTag = channel.basicConsume(queueName, true, callback, consumerTag -> { });
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialise control-plane event consumer", ex);
    }
  }

  ControlPlaneEvents(ControlPlaneEventParser parser) {
    this.connection = null;
    this.channel = null;
    this.queueName = null;
    this.consumerTag = null;
    this.parser = Objects.requireNonNull(parser, "parser");
    this.controlExchange = null;
  }

  private static String requireExchange(String exchange) {
    if (exchange == null) {
      throw new IllegalArgumentException("controlExchange must not be null");
    }
    String trimmed = exchange.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("controlExchange must not be blank");
    }
    return trimmed;
  }

  private void handleDelivery(String tag, Delivery delivery) {
    String routingKey = delivery.getEnvelope().getRoutingKey();
    byte[] body = delivery.getBody();
    try {
      ControlPlaneEventParser.ParsedEvent event = parser.parse(routingKey, body);
      Instant receivedAt = Instant.now();
      if (event.hasConfirmation()) {
        recordConfirmation(routingKey, event.confirmation(), receivedAt);
      } else if (event.hasStatus()) {
        recordStatus(routingKey, event.status(), receivedAt);
      } else {
        LOGGER.debug("Ignoring unsupported control-plane message on {}", routingKey);
      }
    } catch (IOException ex) {
      LOGGER.warn("Failed to parse confirmation payload on {}", routingKey, ex);
    }
  }

  void recordConfirmation(String routingKey, Confirmation confirmation, Instant receivedAt) {
    confirmations.add(new ConfirmationEnvelope(routingKey, confirmation, receivedAt));
  }

  void recordStatus(String routingKey, StatusEvent status, Instant receivedAt) {
    statuses.add(new StatusEnvelope(routingKey, status, receivedAt));
  }

  public List<ConfirmationEnvelope> confirmations() {
    return new ArrayList<>(confirmations);
  }

  public List<StatusEnvelope> statuses() {
    return new ArrayList<>(statuses);
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

  public List<StatusEnvelope> statusesForSwarm(String swarmId) {
    if (swarmId == null) {
      return List.of();
    }
    return statuses.stream()
        .filter(env -> equalsIgnoreCase(swarmId, env.status().swarmId()))
        .toList();
  }

  public Optional<StatusEnvelope> latestStatus(String swarmId, String role, String instance) {
    return statuses.stream()
        .filter(env -> matchesStatus(env.status(), swarmId, role, instance))
        .max(Comparator.comparing(StatusEnvelope::receivedAt));
  }

  public Optional<StatusEvent> latestStatusEvent(String swarmId, String role, String instance) {
    return latestStatus(swarmId, role, instance).map(StatusEnvelope::status);
  }

  public Optional<StatusEnvelope> latestStatusDelta(String swarmId, String role, String instance) {
    return statuses.stream()
        .filter(env -> isDelta(env.status()) && matchesStatus(env.status(), swarmId, role, instance))
        .max(Comparator.comparing(StatusEnvelope::receivedAt));
  }

  public Optional<StatusEvent> latestStatusDeltaEvent(String swarmId, String role, String instance) {
    return latestStatusDelta(swarmId, role, instance).map(StatusEnvelope::status);
  }

  public Optional<Instant> lastStatusSeenAt(String swarmId, String role, String instance) {
    return latestStatus(swarmId, role, instance).map(StatusEnvelope::receivedAt);
  }

  public void assertWorkQueues(String swarmId, String role, String instance,
      List<String> expectedIn, List<String> expectedRoutes, List<String> expectedOut) {
    StatusEvent status = latestStatusEvent(swarmId, role, instance)
        .orElseThrow(() -> new AssertionError("No status event recorded for " + describe(swarmId, role, instance)));
    StatusEvent.QueueEndpoints queues = status.queues().work();
    assertQueueSection("work.in", expectedIn, queues == null ? List.of() : queues.in());
    assertQueueSection("work.routes", expectedRoutes, queues == null ? List.of() : queues.routes());
    assertQueueSection("work.out", expectedOut, queues == null ? List.of() : queues.out());
  }

  public void assertControlQueues(String swarmId, String role, String instance,
      List<String> expectedIn, List<String> expectedRoutes, List<String> expectedOut) {
    StatusEvent status = latestStatusEvent(swarmId, role, instance)
        .orElseThrow(() -> new AssertionError("No status event recorded for " + describe(swarmId, role, instance)));
    StatusEvent.QueueEndpoints queues = status.queues().control();
    assertQueueSection("control.in", expectedIn, queues == null ? List.of() : queues.in());
    assertQueueSection("control.routes", expectedRoutes, queues == null ? List.of() : queues.routes());
    assertQueueSection("control.out", expectedOut, queues == null ? List.of() : queues.out());
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

  private boolean matchesStatus(StatusEvent status, String swarmId, String role, String instance) {
    if (status == null) {
      return false;
    }
    return equalsIgnoreCase(swarmId, status.swarmId())
        && equalsIgnoreCase(role, status.role())
        && equalsIgnoreCase(instance, status.instance());
  }

  private boolean isDelta(StatusEvent status) {
    return status != null && "status-delta".equalsIgnoreCase(status.kind());
  }

  private boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return left.equalsIgnoreCase(right);
  }

  private void assertQueueSection(String section, List<String> expected, List<String> actual) {
    List<String> expectedList = expected == null ? List.of() : List.copyOf(expected);
    List<String> actualList = actual == null ? List.of() : List.copyOf(actual);
    if (!expectedList.equals(actualList)) {
      throw new AssertionError(String.format("Queue section %s mismatch. expected=%s actual=%s", section, expectedList, actualList));
    }
  }

  private String describe(String swarmId, String role, String instance) {
    return "swarm=" + swarmId + ", role=" + role + ", instance=" + instance;
  }

  public record ConfirmationEnvelope(String routingKey, Confirmation confirmation, Instant receivedAt) {
  }

  public record StatusEnvelope(String routingKey, StatusEvent status, Instant receivedAt) {
  }
}
