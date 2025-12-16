package io.pockethive.e2e.support;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.Delivery;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;

/**
 * Collects control-plane outcomes/alerts/status metrics from RabbitMQ for later assertions.
 */
public final class ControlPlaneEvents implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ControlPlaneEvents.class);

  private final org.springframework.amqp.rabbit.connection.Connection connection;
  private final Channel channel;
  private final String queueName;
  private final String consumerTag;
  private final ControlPlaneEventParser parser;
  private final List<OutcomeEnvelope> outcomes = new CopyOnWriteArrayList<>();
  private final List<AlertEnvelope> alerts = new CopyOnWriteArrayList<>();
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
      channel.queueBind(queueName, this.controlExchange, "event.outcome.#");
      channel.queueBind(queueName, this.controlExchange, "event.alert.#");
      channel.queueBind(queueName, this.controlExchange, "event.metric.status-full.#");
      channel.queueBind(queueName, this.controlExchange, "event.metric.status-delta.#");
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
      if (event.hasOutcome()) {
        recordOutcome(routingKey, event.outcome(), receivedAt);
      } else if (event.hasAlert()) {
        recordAlert(routingKey, event.alert(), receivedAt);
      } else if (event.hasStatus()) {
        recordStatus(routingKey, event.status(), receivedAt);
      } else {
        LOGGER.debug("Ignoring unsupported control-plane message on {}", routingKey);
      }
    } catch (IOException ex) {
      LOGGER.warn("Failed to parse control-plane payload on {}", routingKey, ex);
    }
  }

  void recordOutcome(String routingKey, CommandOutcome outcome, Instant receivedAt) {
    outcomes.add(new OutcomeEnvelope(routingKey, outcome, receivedAt));
  }

  void recordAlert(String routingKey, AlertMessage alert, Instant receivedAt) {
    alerts.add(new AlertEnvelope(routingKey, alert, receivedAt));
  }

  void recordStatus(String routingKey, StatusEvent status, Instant receivedAt) {
    statuses.add(new StatusEnvelope(routingKey, status, receivedAt));
  }

  public List<OutcomeEnvelope> outcomes() {
    return new ArrayList<>(outcomes);
  }

  public List<AlertEnvelope> alerts() {
    return new ArrayList<>(alerts);
  }

  public List<StatusEnvelope> statuses() {
    return new ArrayList<>(statuses);
  }

  public Optional<OutcomeEnvelope> findOutcome(String type, String correlationId) {
    if (type == null || correlationId == null) {
      return Optional.empty();
    }
    String expectedType = type.trim().toLowerCase(Locale.ROOT);
    String expectedCorrelation = correlationId.trim();
    return outcomes.stream()
        .filter(env -> env.outcome() != null
            && env.outcome().type() != null
            && expectedType.equals(env.outcome().type().toLowerCase(Locale.ROOT))
            && expectedCorrelation.equals(env.outcome().correlationId()))
        .findFirst();
  }

  public Optional<CommandOutcome> outcome(String type, String correlationId) {
    return findOutcome(type, correlationId).map(OutcomeEnvelope::outcome);
  }

  public long outcomeCount(String type) {
    if (type == null || type.isBlank()) {
      return 0L;
    }
    String expectedType = type.trim().toLowerCase(Locale.ROOT);
    return outcomes.stream()
        .map(OutcomeEnvelope::outcome)
        .filter(Objects::nonNull)
        .filter(outcome -> outcome.type() != null && expectedType.equals(outcome.type().toLowerCase(Locale.ROOT)))
        .count();
  }

  public List<AlertMessage> alertsForCorrelation(String correlationId) {
    if (correlationId == null || correlationId.isBlank()) {
      return List.of();
    }
    String expected = correlationId.trim();
    return alerts.stream()
        .map(AlertEnvelope::alert)
        .filter(Objects::nonNull)
        .filter(alert -> expected.equals(alert.correlationId()))
        .toList();
  }

  public boolean hasMessageOnRoutingKey(String routingKey) {
    if (routingKey == null || routingKey.isBlank()) {
      return false;
    }
    String expected = routingKey.trim();
    return outcomes.stream().anyMatch(env -> expected.equals(env.routingKey()))
        || alerts.stream().anyMatch(env -> expected.equals(env.routingKey()))
        || statuses.stream().anyMatch(env -> expected.equals(env.routingKey()));
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
    if (!"status-full".equalsIgnoreCase(status.type())) {
      throw new AssertionError("Expected status-full for queue assertions but saw type=" + status.type());
    }
    StatusEvent.Queues queues = status.data().io().work().queues();
    assertQueueSection("work.in", expectedIn, queues == null ? List.of() : queues.in());
    assertQueueSection("work.routes", expectedRoutes, queues == null ? List.of() : queues.routes());
    assertQueueSection("work.out", expectedOut, queues == null ? List.of() : queues.out());
  }

  public void assertControlQueues(String swarmId, String role, String instance,
      List<String> expectedIn, List<String> expectedRoutes, List<String> expectedOut) {
    StatusEvent status = latestStatusEvent(swarmId, role, instance)
        .orElseThrow(() -> new AssertionError("No status event recorded for " + describe(swarmId, role, instance)));
    if (!"status-full".equalsIgnoreCase(status.type())) {
      throw new AssertionError("Expected status-full for queue assertions but saw type=" + status.type());
    }
    StatusEvent.Queues queues = status.data().io().control().queues();
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

  private boolean matchesStatus(StatusEvent status, String swarmId, String role, String instance) {
    if (status == null) {
      return false;
    }
    return equalsIgnoreCase(swarmId, status.swarmId())
        && rolesEqual(role, status.role())
        && equalsIgnoreCase(instance, status.instance());
  }

  private boolean isDelta(StatusEvent status) {
    return status != null && "status-delta".equalsIgnoreCase(status.type());
  }

  private boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return left.equalsIgnoreCase(right);
  }

  private boolean rolesEqual(String expected, String actual) {
    String normalizedExpected = normalizeRole(expected);
    String normalizedActual = normalizeRole(actual);
    if (normalizedExpected == null || normalizedActual == null) {
      return false;
    }
    return normalizedExpected.equals(normalizedActual);
  }

  private String normalizeRole(String role) {
    if (role == null) {
      return null;
    }
    String trimmed = role.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
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

  public record OutcomeEnvelope(String routingKey, CommandOutcome outcome, Instant receivedAt) {
  }

  public record AlertEnvelope(String routingKey, AlertMessage alert, Instant receivedAt) {
  }

  public record StatusEnvelope(String routingKey, StatusEvent status, Instant receivedAt) {
  }
}
