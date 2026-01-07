package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import java.util.Objects;

/**
 * Control-plane publisher wrapper that appends outgoing envelopes to a {@link SwarmJournal}.
 *
 * <p>This is the canonical hook for swarm-scoped journaling of control-plane outputs
 * (signals/outcomes/alerts), excluding status metrics.</p>
 */
public final class JournalControlPlanePublisher implements ControlPlanePublisher {

  private final ObjectMapper mapper;
  private final SwarmJournal journal;
  private final ControlPlanePublisher delegate;

  public JournalControlPlanePublisher(ObjectMapper mapper, SwarmJournal journal, ControlPlanePublisher delegate) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    this.journal = Objects.requireNonNull(journal, "journal");
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void publishSignal(SignalMessage message) {
    Objects.requireNonNull(message, "message");
    delegate.publishSignal(message);
    String routingKey = message.routingKey();
    if (routingKey == null || routingKey.isBlank()) {
      return;
    }
    if (routingKey.startsWith("signal.status-request.")) {
      return;
    }
    ControlSignal signal = tryParse(message.payload(), ControlSignal.class);
    if (signal == null) {
      return;
    }
    journal.append(SwarmJournalEntries.outSignal(mapper, routingKey, signal));
  }

  @Override
  public void publishEvent(EventMessage message) {
    Objects.requireNonNull(message, "message");
    delegate.publishEvent(message);
    String routingKey = message.routingKey();
    if (routingKey == null || routingKey.isBlank()) {
      return;
    }
    if (routingKey.startsWith("event.metric.status-")) {
      return;
    }
    if (routingKey.startsWith("event.outcome.")) {
      CommandOutcome outcome = tryParse(message.payload(), CommandOutcome.class);
      if (outcome != null) {
        journal.append(SwarmJournalEntries.outOutcome(mapper, routingKey, outcome));
      }
      return;
    }
    if (routingKey.startsWith("event.alert.")) {
      AlertMessage alert = tryParse(message.payload(), AlertMessage.class);
      if (alert != null) {
        journal.append(SwarmJournalEntries.outAlert(mapper, routingKey, alert));
      }
    }
  }

  private <T> T tryParse(Object payload, Class<T> type) {
    if (payload == null) {
      return null;
    }
    if (type.isInstance(payload)) {
      return type.cast(payload);
    }
    try {
      if (payload instanceof String s) {
        return mapper.readValue(s, type);
      }
      return mapper.convertValue(payload, type);
    } catch (Exception ignored) {
      return null;
    }
  }
}
