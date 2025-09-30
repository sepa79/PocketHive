package io.pockethive.e2e.support;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
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
  private final List<ComponentStatusEnvelope> componentStatuses = new CopyOnWriteArrayList<>();

  public ControlPlaneEvents(ConnectionFactory connectionFactory) {
    Objects.requireNonNull(connectionFactory, "connectionFactory");
    try {
      this.connection = connectionFactory.createConnection();
      this.channel = connection.createChannel(false);
      this.parser = new ControlPlaneEventParser();
      this.queueName = channel.queueDeclare("", false, true, true, Collections.emptyMap()).getQueue();
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.ready.#");
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.error.#");
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.status-full.#");
      channel.queueBind(queueName, Topology.CONTROL_EXCHANGE, "ev.status-delta.#");
      DeliverCallback callback = this::handleDelivery;
      this.consumerTag = channel.basicConsume(queueName, true, callback, consumerTag -> { });
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to initialise control-plane event consumer", ex);
    }
  }

  private void handleDelivery(String tag, Delivery delivery) {
    String routingKey = delivery.getEnvelope().getRoutingKey();
    byte[] body = delivery.getBody();
    Instant receivedAt = Instant.now();
    try {
      ControlPlaneEventParser.StatusPayload statusPayload = parser.parseStatus(routingKey, body);
      if (statusPayload != null) {
        componentStatuses.add(new ComponentStatusEnvelope(routingKey, statusPayload.swarmId(),
            statusPayload.role(), statusPayload.enabled(), statusPayload.timestamp(), receivedAt));
        return;
      }

      Confirmation confirmation = parser.parse(routingKey, body);
      if (confirmation != null) {
        confirmations.add(new ConfirmationEnvelope(routingKey, confirmation, receivedAt));
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

  public List<ComponentStatusEnvelope> componentStatuses() {
    return new ArrayList<>(componentStatuses);
  }

  public Optional<Boolean> latestEnabledForRole(String swarmId, String role, Instant notBefore) {
    if (swarmId == null || role == null) {
      return Optional.empty();
    }
    return latestStatusByRole(swarmId, notBefore).stream()
        .filter(env -> role.equalsIgnoreCase(env.role()))
        .map(ComponentStatusEnvelope::enabled)
        .filter(Objects::nonNull)
        .findFirst();
  }

  public Optional<Boolean> latestEnabledForRole(String swarmId, String role) {
    return latestEnabledForRole(swarmId, role, null);
  }

  public Map<String, Boolean> latestEnabledByRole(String swarmId, Instant notBefore) {
    Map<String, Boolean> result = new HashMap<>();
    for (ComponentStatusEnvelope envelope : latestStatusByRole(swarmId, notBefore)) {
      if (envelope.enabled() != null) {
        result.put(envelope.role(), envelope.enabled());
      }
    }
    return result;
  }

  public Map<String, Boolean> latestEnabledByRole(String swarmId) {
    return latestEnabledByRole(swarmId, null);
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

  public record ComponentStatusEnvelope(String routingKey, String swarmId, String role, Boolean enabled,
      Instant eventTimestamp, Instant receivedAt) {

    public Instant effectiveTimestamp() {
      return eventTimestamp != null ? eventTimestamp : receivedAt;
    }
  }

  private List<ComponentStatusEnvelope> latestStatusByRole(String swarmId, Instant notBefore) {
    if (swarmId == null) {
      return List.of();
    }
    Map<String, ComponentStatusEnvelope> latest = new HashMap<>();
    for (ComponentStatusEnvelope envelope : componentStatuses) {
      if (envelope == null || envelope.role() == null || envelope.role().isBlank()) {
        continue;
      }
      if (envelope.swarmId() == null || !swarmId.equalsIgnoreCase(envelope.swarmId())) {
        continue;
      }
      if (notBefore != null && envelope.effectiveTimestamp() != null
          && envelope.effectiveTimestamp().isBefore(notBefore)) {
        continue;
      }
      String roleKey = envelope.role().toLowerCase(Locale.ROOT);
      ComponentStatusEnvelope existing = latest.get(roleKey);
      if (existing == null || compare(envelope, existing) > 0) {
        latest.put(roleKey, envelope);
      }
    }
    return new ArrayList<>(latest.values());
  }

  private int compare(ComponentStatusEnvelope first, ComponentStatusEnvelope second) {
    Instant firstTs = first != null ? first.effectiveTimestamp() : null;
    Instant secondTs = second != null ? second.effectiveTimestamp() : null;
    if (firstTs == null && secondTs == null) {
      return 0;
    }
    if (firstTs == null) {
      return -1;
    }
    if (secondTs == null) {
      return 1;
    }
    return firstTs.compareTo(secondTs);
  }
}
