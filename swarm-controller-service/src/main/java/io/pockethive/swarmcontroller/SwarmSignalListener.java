package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.runtime.SwarmControlPlaneJournalErrors;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import io.pockethive.swarmcontroller.runtime.SwarmJournalEntries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Responsibility: Receive control-plane AMQP messages, establish diagnostic context, and dispatch transport triggers.
 * Must not: Own lifecycle transitions, config application, observation state, projections, or terminal outcomes.
 * Contract: Decode each accepted envelope once and delegate it to the single owner of its responsibility.
 */
@Component
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private final String instanceId;
  private final ObjectMapper mapper;
  private final ManagerControlPlane controlPlane;
  private final String swarmId;
  private final String role;
  private final SwarmJournal journal;
  private final SwarmControlPlaneJournalErrors journalErrors;
  private final SwarmWorkerStatusHandler workerStatuses;
  private final SwarmWorkerAlertHandler workerAlerts;
  private final SwarmControllerStatusPublisher statusPublisher;
  private final SwarmStatusFullCoordinator statusFullCoordinator;
  private final SwarmLifecycleCommandHandler lifecycleCommands;
  private final SwarmConfigUpdateHandler configUpdates;
  private final SwarmRemoveCommandHandler removeCommands;
  private final ControlPlaneCodec controlPlaneCodec;

  @Autowired
  public SwarmSignalListener(@Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper,
                             SwarmControllerProperties properties,
                             SwarmJournal journal,
                             ManagerControlPlane controlPlane,
                             SwarmRemoveCommandHandler removeCommands,
                             SwarmWorkerStatusHandler workerStatuses,
                             SwarmWorkerAlertHandler workerAlerts,
                             SwarmControllerStatusPublisher statusPublisher,
                             SwarmStatusFullCoordinator statusFullCoordinator,
                             SwarmLifecycleCommandHandler lifecycleCommands,
                             SwarmConfigUpdateHandler configUpdates,
                             ControlPlaneCodec controlPlaneCodec) {
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.removeCommands = Objects.requireNonNull(removeCommands, "removeCommands");
    this.workerStatuses = Objects.requireNonNull(workerStatuses, "workerStatuses");
    this.workerAlerts = Objects.requireNonNull(workerAlerts, "workerAlerts");
    this.swarmId = properties.getSwarmId();
    this.role = properties.getRole();
    this.journal = Objects.requireNonNull(journal, "journal");
    this.journalErrors = new SwarmControlPlaneJournalErrors(this.journal, swarmId, role, instanceId, "swarm-signal-listener");
    this.controlPlaneCodec = Objects.requireNonNull(controlPlaneCodec, "controlPlaneCodec");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.statusPublisher = Objects.requireNonNull(statusPublisher, "statusPublisher");
    this.statusFullCoordinator = Objects.requireNonNull(statusFullCoordinator, "statusFullCoordinator");
    this.lifecycleCommands = Objects.requireNonNull(lifecycleCommands, "lifecycleCommands");
    this.configUpdates = Objects.requireNonNull(configUpdates, "configUpdates");
  }

  @RabbitListener(queues = "#{swarmControllerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    // Control-plane messages must never be requeued on failures: ACK (drop) always to avoid storms.
    try {
      if (routingKey == null || routingKey.isBlank()) {
        log.warn("Received control message with null or blank routing key; payload snippet={}", snippet(body));
        journalErrors.errorDrop("event-dropped", routingKey, "missing routing key", body, null);
        return;
      }
      if (body == null || body.isBlank()) {
        log.warn("Received control message with null or blank payload for routing key {}", routingKey);
        journalErrors.errorDrop("event-dropped", routingKey, "missing payload", body, null);
        return;
      }
      String snippet = snippet(body);
      RoutingKey signalKey = ControlPlaneRouting.parseSignal(routingKey);
      RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
      boolean statusEvent = eventKey != null
          && (ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(eventKey.type())
              || ControlPlaneEventTypes.METRIC_STATUS_DELTA.equals(eventKey.type()));
      boolean alertEvent = eventKey != null
          && ControlPlaneEventTypes.ALERT_ALERT.equals(eventKey.type());
      if (statusEvent
          || (signalKey != null && ControlPlaneSignals.STATUS_REQUEST.equals(signalKey.type()))) {
        log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      } else {
        log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      }
      if (signalKey != null) {
        boolean processed = controlPlane.consume(body, routingKey, envelope -> {
          RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
          if (!shouldAcceptSignal(key)) {
            log.debug("Ignoring control signal on routing key {}", envelope.routingKey());
            return;
          }
          handleSignal(envelope);
        });
        if (!processed) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
        }
        return;
      } else if (statusEvent) {
        handleStatusEvent(routingKey, body);
      } else if (alertEvent) {
        handleAlertEvent(routingKey, body);
      } else {
        log.warn("Ignoring unsupported control-plane routing key {}; payload snippet={}", routingKey, snippet(body));
        journalErrors.errorDrop("event-dropped", routingKey, "unsupported routing key", body, null);
      }
    } catch (Exception e) {
      log.warn("Ignoring control-plane message due to handler exception; rk={} payload snippet={}", routingKey, snippet(body), e);
      journalErrors.errorDrop("event-dropped", routingKey, "handler exception", body, e);
    } finally {
      MDC.clear();
    }
  }

  private void handleStatusEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      log.warn("Received status event with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "unparseable routing key", body, null);
      return;
    }
    String role = eventKey.role();
    if (role == null || role.isBlank()) {
      log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "missing role segment", body, null);
      return;
    }
    String instance = eventKey.instance();
    if (instance == null || instance.isBlank()) {
      log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("status-parse-error", routingKey, "missing instance segment", body, null);
      return;
    }
    try {
      io.pockethive.control.StatusMetric status =
          controlPlaneCodec.decode(body, routingKey, io.pockethive.control.StatusMetric.class);
      if (!isLocalSwarm(eventKey.swarmId())) {
        log.debug("Ignoring status for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
        return;
      }
      if (this.role.equalsIgnoreCase(role) && this.instanceId.equalsIgnoreCase(instance)) {
        // Do not treat controller self-status as a worker heartbeat; it skews totals by +1.
        return;
      }
      boolean isStatusFull = isStatusFullEvent(eventKey);
      if (workerStatuses.observe(role, instance, status, isStatusFull)) {
        statusFullCoordinator.maybePublishStartupReady();
      }
      if (isStatusFull) {
        statusPublisher.publishFull();
      }
      lifecycleCommands.tryComplete();
      statusFullCoordinator.maybePublishPending();
    } catch (Exception e) {
      log.warn("status parse", e);
      journalErrors.errorDrop("status-parse-error", routingKey, "payload parse", body, e);
    }
  }

  private void handleAlertEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      log.warn("Received alert with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      journalErrors.errorDrop("alert-parse-error", routingKey, "unparseable routing key", body, null);
      return;
    }
    try {
      AlertMessage alert = controlPlaneCodec.decode(body, routingKey, AlertMessage.class);
      if (!isLocalSwarm(eventKey.swarmId())) {
        log.debug("Ignoring alert for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
        return;
      }
      String payloadSwarm = alert != null && alert.scope() != null ? alert.scope().swarmId() : null;
      String payloadRole = alert != null && alert.scope() != null ? alert.scope().role() : null;
      String payloadInstance = alert != null && alert.scope() != null ? alert.scope().instance() : null;
      warnMissingScopeFields("alert", routingKey, body, payloadSwarm, payloadRole, payloadInstance);
      workerAlerts.handle(routingKey, alert).ifPresent(lifecycleCommands::failPending);
    } catch (Exception e) {
      log.warn("alert parse", e);
      journalErrors.errorDrop("alert-parse-error", routingKey, "payload parse", body, e);
    }
  }

  private void warnMissingScopeFields(String label,
                                      String routingKey,
                                      String body,
                                      String swarmId,
                                      String role,
                                      String instance) {
    java.util.List<String> missing = new java.util.ArrayList<>();
    if (swarmId == null || swarmId.isBlank()) {
      missing.add("swarmId");
    }
    if (role == null || role.isBlank()) {
      missing.add("role");
    }
    if (instance == null || instance.isBlank()) {
      missing.add("instance");
    }
    if (!missing.isEmpty()) {
      log.warn("Received {} payload with missing scope fields {}; rk={} payload snippet={}",
          label, missing, routingKey, snippet(body));
      journalErrors.errorDrop("event-dropped", routingKey, "missing scope fields: " + String.join(",", missing), body, null);
    }
  }

  private void processConfigUpdate(ControlSignalEnvelope envelope) {
    RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
    if (!shouldProcessConfigUpdate(key)) {
      log.debug("Ignoring config-update on routing key {}", envelope.routingKey());
      return;
    }
    configUpdates.handle(envelope.signal(), swarmIdOrDefault(envelope.signal()));
  }

  private void handleSignal(ControlSignalEnvelope envelope) {
    ControlSignal cs = envelope.signal();
    if (cs == null) {
      return;
    }
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());
    String signal = resolveSignal(envelope);
    if (signal != null && !ControlPlaneSignals.STATUS_REQUEST.equals(signal)) {
      journal.append(SwarmJournalEntries.inSignal(mapper, envelope.routingKey(), cs));
    }
    switch (signal) {
      case ControlPlaneSignals.SWARM_START -> {
        if (isForLocalSwarm(cs)) {
          lifecycleCommands.handle(cs, signal, swarmIdOrDefault(cs));
        }
      }
      case ControlPlaneSignals.SWARM_STOP -> {
        if (isForLocalSwarm(cs)) {
          lifecycleCommands.handle(cs, signal, swarmIdOrDefault(cs));
        }
      }
      case ControlPlaneSignals.SWARM_REMOVE -> {
        if (isForLocalSwarm(cs)) {
          removeCommands.handle(cs);
        }
      }
      case ControlPlaneSignals.STATUS_REQUEST -> {
        log.debug("Status request received: {}", envelope.routingKey());
        statusPublisher.publishFull();
      }
      case ControlPlaneSignals.CONFIG_UPDATE -> processConfigUpdate(envelope);
      default -> {
        // ignore other signals
      }
    }
  }

  private boolean shouldAcceptSignal(RoutingKey key) {
    if (key == null) {
      return false;
    }
    if (!isLocalSwarm(key.swarmId())) {
      return false;
    }
    String roleSegment = defaultSegment(key.role(), role);
    boolean roleMatchesController = role.equalsIgnoreCase(roleSegment) || isAllSegment(roleSegment);
    if (!roleMatchesController) {
      String type = defaultSegment(key.type(), null);
      if (ControlPlaneSignals.CONFIG_UPDATE.equalsIgnoreCase(type)) {
        return true;
      }
      return false;
    }
    String instanceSegment = key.instance();
    if (isAllSegment(instanceSegment)) {
      return true;
    }
    return instanceId.equalsIgnoreCase(instanceSegment);
  }

  private boolean shouldProcessConfigUpdate(RoutingKey key) {
    if (key == null) {
      return false;
    }
    if (!isLocalSwarm(key.swarmId())) {
      return false;
    }
    String roleSegment = defaultSegment(key.role(), role);
    boolean roleMatchesController = role.equalsIgnoreCase(roleSegment) || isAllSegment(roleSegment);
    if (!roleMatchesController) {
      return false;
    }
    String targetInstance = key.instance();
    if (isAllSegment(targetInstance)) {
      return true;
    }
    return instanceId.equalsIgnoreCase(targetInstance);
  }

  private boolean isForLocalSwarm(ControlSignal cs) {
    return appliesToLocalSwarm(cs);
  }

  private String swarmIdOrDefault(ControlSignal cs) {
    String targetSwarm = cs.scope() != null ? cs.scope().swarmId() : null;
    if (targetSwarm == null || targetSwarm.isBlank() || isAllSegment(targetSwarm)) {
      return this.swarmId;
    }
    return targetSwarm;
  }

  private boolean appliesToLocalSwarm(ControlSignal cs) {
    String targetSwarm = cs.scope() != null ? cs.scope().swarmId() : null;
    if (targetSwarm == null || targetSwarm.isBlank() || isAllSegment(targetSwarm)) {
      return true;
    }
    return swarmId.equalsIgnoreCase(targetSwarm);
  }

  private String resolveSignal(ControlSignalEnvelope envelope) {
    return envelope.signal().type();
  }

  private boolean isStatusFullEvent(RoutingKey key) {
    if (key == null || key.type() == null) {
      return false;
    }
    return ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(key.type());
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }

  private String defaultSegment(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private boolean isAllSegment(String value) {
    return ControlScope.isAll(value);
  }

  private boolean isLocalSwarm(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return isAllSegment(value) || swarmId.equalsIgnoreCase(value);
  }
}
