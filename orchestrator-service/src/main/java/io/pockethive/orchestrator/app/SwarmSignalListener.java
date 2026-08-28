package io.pockethive.orchestrator.app;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandResult;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.runtime.RuntimeLogSnapshotJournalService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Decode Orchestrator control-plane ingress and dispatch each supported envelope by kind.
 * Must not: Own domain state transitions, lifecycle convergence, persistence policy, or terminal outcome construction.
 * Contract: Decode once with parsed routing context; journal and drop invalid ingress without a fallback path.
 */
@Component
public class SwarmSignalListener {

  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final String ROLE = ControlPlaneRoles.ORCHESTRATOR;

  private final ControlPlaneCodec codec;
  private final HiveJournal hiveJournal;
  private final ControlPlaneJournalErrors journalErrors;
  private final RuntimeLogSnapshotJournalService runtimeLogSnapshots;
  private final SwarmOperationTerminalHandler terminalOperations;
  private final String instanceId;

  public SwarmSignalListener(
      ControlPlaneCodec codec,
      HiveJournal hiveJournal,
      RuntimeLogSnapshotJournalService runtimeLogSnapshots,
      SwarmOperationTerminalHandler terminalOperations,
      @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
    this.runtimeLogSnapshots = Objects.requireNonNull(runtimeLogSnapshots, "runtimeLogSnapshots");
    this.terminalOperations = Objects.requireNonNull(terminalOperations, "terminalOperations");
    this.instanceId = Objects.requireNonNull(identity, "identity").instanceId();
    this.journalErrors = new ControlPlaneJournalErrors(hiveJournal, ROLE, "swarm-signal-listener");
  }

  @RabbitListener(queues = "#{managerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    try {
      RoutingKey key = requireEventKey(routingKey);
      ControlPlaneEnvelope envelope = codec.decode(body, routingKey);
      if (envelope instanceof StatusMetric) {
        return;
      }
      if (envelope instanceof AlertMessage alert) {
        runtimeLogSnapshots.captureForAlert(routingKey, alert);
        return;
      }
      if (envelope instanceof JournalEvent journal) {
        acceptJournal(key, routingKey, journal);
        return;
      }
      if (!(envelope instanceof CommandResult result)) {
        log.warn("Dropping non-result terminal event rk={}", routingKey);
        journalDrop(key.swarmId(), routingKey, "expected event.result", body, null);
        return;
      }
      terminalOperations.accept(key, routingKey, result);
    } catch (Exception exception) {
      log.warn("Dropping invalid control-plane event rk={} payload={}", routingKey, snippet(body), exception);
      journalDrop(bestEffortSwarmId(routingKey), routingKey, "invalid control-plane event", body, exception);
    }
  }

  private void acceptJournal(RoutingKey key, String routingKey, JournalEvent event) {
    hiveJournal.append(HiveJournalEntry.info(
        key.swarmId(), HiveJournal.Direction.IN, JournalEvent.KIND, event.type(), event.origin(), event.scope(),
        event.correlationId(), event.idempotencyKey(), routingKey, event.data(), null, null));
  }

  private static RoutingKey requireEventKey(String routingKey) {
    RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
    if (key == null || key.type() == null) {
      throw new IllegalArgumentException("Invalid event routing key: " + routingKey);
    }
    return key;
  }

  private void journalDrop(String swarmId, String routingKey, String reason, String body, Exception exception) {
    String resolved = swarmId == null || swarmId.isBlank() ? "hive" : swarmId;
    journalErrors.errorDrop(
        resolved, HiveJournal.Direction.IN, "event-dropped",
        new ControlScope(resolved, ROLE, instanceId), routingKey, reason, body, exception);
  }

  private static String bestEffortSwarmId(String routingKey) {
    RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
    return key == null || ControlScope.isAll(key.swarmId()) ? null : key.swarmId();
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String stripped = payload.strip();
    return stripped.length() <= 300 ? stripped : stripped.substring(0, 300) + "…";
  }
}
