package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import java.util.Objects;

/**
 * Control-plane publisher wrapper that appends outgoing envelopes to a {@link SwarmJournal}.
 *
 * <p>This is the canonical hook for swarm-scoped journaling of control-plane outputs
 * (signals/results/alerts), excluding status metrics.</p>
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
    if (!(message.payload() instanceof ControlSignal signal)) {
      throw new IllegalArgumentException("SignalMessage must carry ControlSignal");
    }
    if (ControlPlaneSignals.STATUS_REQUEST.equals(signal.type())) {
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
    if (message.payload() instanceof StatusMetric) {
      return;
    }
    if (message.payload() instanceof CommandResult result) {
      journal.append(SwarmJournalEntries.outResult(mapper, routingKey, result));
      return;
    }
    if (message.payload() instanceof AlertMessage alert) {
      journal.append(SwarmJournalEntries.outAlert(mapper, routingKey, alert));
    }
  }
}
