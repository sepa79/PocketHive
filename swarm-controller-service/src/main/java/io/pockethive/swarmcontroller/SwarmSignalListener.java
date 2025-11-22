package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandState;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.control.ControlSignal;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private final SwarmLifecycle lifecycle;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final ObjectMapper mapper;
  private final ManagerControlPlane controlPlane;
  private final SwarmControllerProperties properties;
  private final String swarmId;
  private final String role;
  private final String controlExchange;
  private final SwarmDiagnosticsAggregator diagnostics;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final long MAX_STALENESS_MS = 15_000L;
  private volatile boolean controllerEnabled = false;
  private final AtomicReference<PendingTemplate> pendingTemplate = new AtomicReference<>();
  private final AtomicReference<PendingStart> pendingStart = new AtomicReference<>();

  @Autowired
  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper,
                             SwarmControllerProperties properties) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.properties = properties;
    this.swarmId = properties.getSwarmId();
    this.role = properties.getRole();
    this.controlExchange = properties.getControlExchange();
    this.diagnostics = new SwarmDiagnosticsAggregator(this.mapper);
    this.controlPlane = ManagerControlPlane.builder(
        new AmqpControlPlanePublisher(rabbit, controlExchange),
        this.mapper)
        .identity(new ControlPlaneIdentity(swarmId, role, instanceId))
        .duplicateCache(java.time.Duration.ofMinutes(1), 256)
        .build();
    try {
      sendStatusFull();
    } catch (Exception e) {
      log.warn("initial status", e);
    }
  }

  @RabbitListener(queues = "#{swarmControllerControlQueueName}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    try {
      if (routingKey == null || routingKey.isBlank()) {
        log.warn("Received control message with null or blank routing key; payload snippet={}", snippet(body));
        throw new IllegalArgumentException("Control-plane routing key must not be null or blank");
      }
      String snippet = snippet(body);
      if (routingKey.startsWith("ev.status-") || routingKey.startsWith("sig." + ControlPlaneSignals.STATUS_REQUEST)) {
        log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      } else {
        log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
      }
      if (routingKey.startsWith("sig.")) {
        RoutingKey key = ControlPlaneRouting.parseSignal(routingKey);
        if (!shouldAcceptSignal(key)) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
          return;
        }
        boolean processed = controlPlane.consume(body, routingKey, envelope -> handleSignal(envelope, body));
        if (!processed) {
          log.debug("Ignoring control signal on routing key {}", routingKey);
        }
        return;
      } else if (routingKey.startsWith("ev.status-")) {
        handleStatusEvent(routingKey, body);
      } else if (routingKey.startsWith("ev.error.config-update")) {
        handleConfigUpdateErrorEvent(routingKey, body);
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleStatusEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      MissingStatusSegment missingSegment = detectMissingStatusSegment(routingKey);
      if (missingSegment == MissingStatusSegment.ROLE) {
        log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
        throw new IllegalArgumentException("Status event routing key must include a role segment");
      }
      if (missingSegment == MissingStatusSegment.INSTANCE) {
        log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
        throw new IllegalArgumentException("Status event routing key must include an instance segment");
      }
      log.warn("Received status event with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must resolve to a confirmation scope");
    }
    if (!isLocalSwarm(eventKey.swarmId())) {
      log.debug("Ignoring status for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
      return;
    }
    String role = eventKey.role();
    if (role == null || role.isBlank()) {
      log.warn("Received status event with missing role on routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must include a role segment");
    }
    String instance = eventKey.instance();
    if (instance == null || instance.isBlank()) {
      log.warn("Received status event with missing instance on routing key {}; payload snippet={}", routingKey, snippet(body));
      throw new IllegalArgumentException("Status event routing key must include an instance segment");
    }
    try {
      JsonNode node = mapper.readTree(body);
      String payloadSwarm = node.path("swarmId").asText(null);
      if (payloadSwarm != null && !payloadSwarm.isBlank() && !swarmId.equals(payloadSwarm)) {
        log.debug("Ignoring status payload for swarm {} on routing key {}", payloadSwarm, routingKey);
        return;
      }
      lifecycle.updateHeartbeat(role, instance);
      diagnostics.updateFromWorkerStatus(role, instance, node.path("data"));

      JsonNode enabledNode = node.path("enabled");
      boolean enabled = !enabledNode.isMissingNode() && !enabledNode.isNull()
          ? enabledNode.asBoolean()
          : node.path("data").path("enabled").asBoolean(true);
      lifecycle.updateEnabled(role, instance, enabled);
      if (!enabled) {
        boolean ready = lifecycle.markReady(role, instance);
        if (ready) {
          tryEmitPendingTemplateReady();
          tryEmitPendingStartReady();
        }
      }
    } catch (Exception e) {
      log.warn("status parse", e);
    }
  }

  private void handleConfigUpdateErrorEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      log.warn("Received config-update error with unparseable routing key {}; payload snippet={}", routingKey, snippet(body));
      return;
    }
    if (!isLocalSwarm(eventKey.swarmId())) {
      log.debug("Ignoring config-update error for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
      return;
    }
    try {
      ErrorConfirmation confirmation = mapper.readValue(body, ErrorConfirmation.class);
      String role = defaultSegment(eventKey.role(), confirmation.scope() != null ? confirmation.scope().role() : null);
      String instance = defaultSegment(eventKey.instance(), confirmation.scope() != null ? confirmation.scope().instance() : null);
      String message = confirmation.message();
      lifecycle.handleConfigUpdateError(role, instance, message)
          .ifPresent(this::failPendingLifecycle);
    } catch (Exception e) {
      log.warn("config-update error parse", e);
    }
  }

  private void failPendingLifecycle(String reason) {
    boolean failed = false;
    PendingTemplate template = pendingTemplate.getAndSet(null);
    if (template != null) {
      lifecycle.fail(reason);
      emitError(template.signal(), new IllegalStateException(reason), template.resolvedSignal(), template.swarmIdFallback());
      failed = true;
    }
    PendingStart start = pendingStart.getAndSet(null);
    if (start != null) {
      lifecycle.fail(reason);
      emitError(start.signal(), new IllegalStateException(reason), start.resolvedSignal(), start.swarmIdFallback());
      failed = true;
    }
    if (!failed) {
      lifecycle.fail(reason);
      log.warn("Config-update error received with no pending lifecycle command. reason={}", reason);
    }
  }

  private void processSwarmSignal(ControlSignal cs,
                                  String resolvedSignal,
                                  String swarmId,
                                  SignalAction action,
                                  String label) {
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());
    try {
      log.info("{} signal for swarm {}", label.substring(0, 1).toUpperCase() + label.substring(1), swarmId);
      action.apply(serializeArgs(cs));
      if (ControlPlaneSignals.SWARM_TEMPLATE.equals(resolvedSignal)) {
        onTemplateSuccess(cs, resolvedSignal, swarmId);
      } else if (ControlPlaneSignals.SWARM_START.equals(resolvedSignal)) {
        onStartSuccess(cs, resolvedSignal, swarmId);
      } else {
        emitSuccess(cs, resolvedSignal, swarmId);
      }
    } catch (Exception e) {
      log.warn(label, e);
      emitError(cs, e, resolvedSignal, swarmId);
    }
  }

  private void onTemplateSuccess(ControlSignal cs, String resolvedSignal, String swarmId) {
    if (lifecycle.isReadyForWork()) {
      pendingTemplate.set(null);
      emitSuccess(cs, resolvedSignal, swarmId);
      return;
    }

    PendingTemplate newPending = new PendingTemplate(cs, resolvedSignal, swarmId);
    PendingTemplate previous = pendingTemplate.getAndSet(newPending);
    if (previous != null) {
      log.debug("Replacing pending swarm-template confirmation for correlation {} with {}", previous.signal().correlationId(), cs.correlationId());
    }
    tryEmitPendingTemplateReady();
  }

  private void tryEmitPendingTemplateReady() {
    while (true) {
      PendingTemplate pending = pendingTemplate.get();
      if (pending == null) {
        return;
      }
      if (!lifecycle.isReadyForWork()) {
        return;
      }
      if (pendingTemplate.compareAndSet(pending, null)) {
        emitSuccess(pending.signal(), pending.resolvedSignal(), pending.swarmIdFallback());
        return;
      }
    }
  }

  private void onStartSuccess(ControlSignal cs, String resolvedSignal, String swarmId) {
    if (!lifecycle.hasPendingConfigUpdates() && lifecycle.isReadyForWork()) {
      pendingStart.set(null);
      emitSuccess(cs, resolvedSignal, swarmId);
      return;
    }
    PendingStart newPending = new PendingStart(cs, resolvedSignal, swarmId);
    PendingStart previous = pendingStart.getAndSet(newPending);
    if (previous != null) {
      log.debug("Replacing pending swarm-start confirmation for correlation {} with {}",
          previous.signal().correlationId(), cs.correlationId());
    }
    tryEmitPendingStartReady();
  }

  private void tryEmitPendingStartReady() {
    while (true) {
      PendingStart pending = pendingStart.get();
      if (pending == null) {
        return;
      }
      if (lifecycle.hasPendingConfigUpdates() || !lifecycle.isReadyForWork()) {
        return;
      }
      if (pendingStart.compareAndSet(pending, null)) {
        emitSuccess(pending.signal(), pending.resolvedSignal(), pending.swarmIdFallback());
        return;
      }
    }
  }

  private void processConfigUpdate(ControlSignalEnvelope envelope, String rawPayload, String resolvedSignal) {
    ControlSignal cs = envelope.signal();
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());

    CommandTarget commandTarget = effectiveCommandTarget(cs);
    RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
    if (!shouldProcessConfigUpdate(key, commandTarget, cs)) {
      log.debug("Ignoring config-update on routing key {}", envelope.routingKey());
      return;
    }

    JsonNode node = mapper.createObjectNode();
    if (cs.args() != null) {
      node = mapper.valueToTree(cs.args());
    }

    try {
      JsonNode dataNode = node.path("data");
      Boolean enabledFlag = dataNode.has("enabled") ? dataNode.path("enabled").asBoolean() : null;

      Boolean stateEnabled = null;
      Map<String, Object> details = new LinkedHashMap<>();

      List<Runnable> fanouts = new ArrayList<>();
      boolean fromSelf = cs.origin() != null && instanceId.equalsIgnoreCase(cs.origin());

      switch (commandTarget) {
        case SWARM -> {
          if (enabledFlag != null && appliesToLocalSwarm(cs)) {
            if (!fromSelf) {
              lifecycle.setSwarmEnabled(enabledFlag);
            }
            controllerEnabled = enabledFlag;
            sendStatusDelta();
            stateEnabled = enabledFlag;
            details.put("workloads", Map.of("enabled", enabledFlag));
          } else if (!fromSelf) {
            fanouts.add(() -> forwardToAll(cs, rawPayload));
          }
        }
        case INSTANCE -> {
          if (isControllerCommand(cs)) {
            if (enabledFlag != null) {
              controllerEnabled = enabledFlag;
              lifecycle.setControllerEnabled(enabledFlag);
              sendStatusDelta();
              stateEnabled = enabledFlag;
              details.put("controller", Map.of("enabled", enabledFlag));
            }
          } else {
            TargetSpec spec = resolveInstanceTarget(cs);
            if (spec == null) {
              throw new IllegalArgumentException("commandTarget=instance requires role and instance fields");
            }
            if (!fromSelf) {
              fanouts.add(() -> forwardToInstance(cs, rawPayload, spec));
            }
          }
        }
        case ROLE -> {
          String roleTarget = resolveRoleTarget(cs);
          if (roleTarget == null) {
            throw new IllegalArgumentException("commandTarget=role requires a role field");
          }
          if (!fromSelf) {
            fanouts.add(() -> forwardToRole(cs, rawPayload, roleTarget));
          }
        }
        case ALL -> {
          if (!fromSelf) {
            fanouts.add(() -> forwardToAll(cs, rawPayload));
          }
        }
      }

      CommandState state = configCommandState(cs, resolvedSignal, stateEnabled, details);
      fanouts.forEach(Runnable::run);
      emitSuccess(cs, resolvedSignal, null, state);
    } catch (Exception e) {
      log.warn("config update", e);
      emitError(cs, e, resolvedSignal, null);
    }
  }

  private void handleSignal(ControlSignalEnvelope envelope, String rawPayload) {
    ControlSignal cs = envelope.signal();
    if (cs == null) {
      return;
    }
    String signal = resolveSignal(envelope);
    switch (signal) {
      case ControlPlaneSignals.SWARM_TEMPLATE -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> lifecycle.prepare(args), "template");
        }
      }
      case ControlPlaneSignals.SWARM_START -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.start(args);
            controllerEnabled = true;
            sendStatusFull();
          }, "start");
        }
      }
      case ControlPlaneSignals.SWARM_STOP -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> {
            lifecycle.stop();
            controllerEnabled = false;
          }, "stop");
        }
      }
      case ControlPlaneSignals.SWARM_REMOVE -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, signal, swarmIdOrDefault(cs), args -> lifecycle.remove(), "remove");
        }
      }
      case ControlPlaneSignals.STATUS_REQUEST -> {
        log.debug("Status request received: {}", envelope.routingKey());
        sendStatusFull();
      }
      case ControlPlaneSignals.CONFIG_UPDATE -> processConfigUpdate(envelope, rawPayload, signal);
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

  private boolean shouldProcessConfigUpdate(RoutingKey key, CommandTarget commandTarget, ControlSignal cs) {
    if (key == null) {
      return false;
    }
    if (!isLocalSwarm(key.swarmId())) {
      return false;
    }
    String roleSegment = defaultSegment(key.role(), role);
    boolean roleMatchesController = role.equalsIgnoreCase(roleSegment) || isAllSegment(roleSegment);
    if (!roleMatchesController) {
      return commandTarget == CommandTarget.INSTANCE || commandTarget == CommandTarget.ROLE;
    }
    String targetInstance = key.instance();
    if (isAllSegment(targetInstance)) {
      if (commandTarget == CommandTarget.SWARM) {
        return appliesToLocalSwarm(cs);
      }
      return commandTarget == CommandTarget.ALL;
    }
    if (instanceId.equalsIgnoreCase(targetInstance)) {
      return true;
    }
    return false;
  }

  @FunctionalInterface
  private interface SignalAction {
    void apply(String argsJson) throws Exception;
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    sendStatusDelta();
    tryEmitPendingStartReady();
  }

  private void emitSuccess(ControlSignal cs, String resolvedSignal, String swarmIdFallback) {
    emitSuccess(cs, resolvedSignal, swarmIdFallback, null);
  }

  private void emitSuccess(ControlSignal cs,
                           String resolvedSignal,
                           String swarmIdFallback,
                           CommandState overrideState) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Ready confirmation");
    CommandState baseState = overrideState != null ? overrideState : stateForSuccess(signal);
    CommandState state = enrichState(cs, baseState);
    String type = successEventType(signal);
    ReadyConfirmation confirmation = new ReadyConfirmation(
        Instant.now(),
        cs.correlationId(),
        cs.idempotencyKey(),
        signal,
        scope,
        state
    );
    String json = toJson(confirmation);
    sendControl(ControlPlaneRouting.event(type, scope), json, "ev.ready");
  }

  private void emitError(ControlSignal cs,
                         Exception e,
                         String resolvedSignal,
                         String swarmIdFallback) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Error confirmation");
    CommandState state = enrichState(cs, stateForError(signal));
    String type = errorEventType(signal);
    ErrorConfirmation confirmation = new ErrorConfirmation(
        Instant.now(),
        cs.correlationId(),
        cs.idempotencyKey(),
        signal,
        scope,
        state,
        phaseForSignal(signal),
        e.getClass().getSimpleName(),
        e.getMessage(),
        Boolean.FALSE,
        null
    );
    String json = toJson(confirmation);
    sendControl(ControlPlaneRouting.event(type, scope), json, "ev.error");
  }

  private ConfirmationScope scopeFor(ControlSignal cs, String swarmIdFallback) {
    String swarmId = defaultSegment(cs.swarmId(), swarmIdFallback);
    swarmId = defaultSegment(swarmId, swarmId);
    String role = defaultSegment(cs.role(), this.role);
    String instance = defaultSegment(cs.instance(), instanceId);
    return new ConfirmationScope(swarmId, role, instance);
  }

  private CommandState stateForSuccess(String signal) {
    String effectiveSignal = signal == null ? "" : signal;
    String status = switch (effectiveSignal) {
      case ControlPlaneSignals.SWARM_TEMPLATE -> "Ready";
      case ControlPlaneSignals.SWARM_START -> "Running";
      case ControlPlaneSignals.SWARM_STOP -> "Stopped";
      case ControlPlaneSignals.SWARM_REMOVE -> "Removed";
      case ControlPlaneSignals.CONFIG_UPDATE -> stateFromLifecycle();
      default -> stateFromLifecycle();
    };
    return new CommandState(status, null, null);
  }

  private String successEventType(String signal) {
    String nonBlankSignal = requireSignal(signal, "Ready event type");
    if (nonBlankSignal.startsWith("swarm-")) {
      return "ready." + nonBlankSignal;
    }
    if (ControlPlaneSignals.CONFIG_UPDATE.equals(nonBlankSignal)) {
      return "ready.config-update";
    }
    return "ready." + nonBlankSignal;
  }

  private String errorEventType(String signal) {
    String nonBlankSignal = requireSignal(signal, "Error event type");
    if (nonBlankSignal.startsWith("swarm-")) {
      return "error." + nonBlankSignal;
    }
    if (ControlPlaneSignals.CONFIG_UPDATE.equals(nonBlankSignal)) {
      return "error.config-update";
    }
    return "error." + nonBlankSignal;
  }

  private CommandState configCommandState(ControlSignal cs,
                                          String resolvedSignal,
                                          Boolean enabled,
                                          Map<String, Object> details) {
    String signal = requireSignal(confirmationSignal(cs, resolvedSignal), "Config confirmation state");
    CommandState base = stateForSuccess(signal);
    Map<String, Object> detailCopy = (details == null || details.isEmpty()) ? null : new LinkedHashMap<>(details);
    return new CommandState(base.status(), enabled, detailCopy);
  }

  private CommandState enrichState(ControlSignal cs, CommandState baseState) {
    CommandTarget commandTarget = effectiveCommandTarget(cs);
    String status = baseState != null ? baseState.status() : null;
    Boolean enabled = baseState != null ? baseState.enabled() : null;
    Map<String, Object> details = baseState != null ? baseState.details() : null;
    Map<String, Object> mirroredDetails = mirrorEnablement(details, enabled, commandTarget, cs);
    return new CommandState(status, enabled, mirroredDetails);
  }

  private Map<String, Object> mirrorEnablement(Map<String, Object> details,
                                               Boolean enabled,
                                               CommandTarget commandTarget,
                                               ControlSignal cs) {
    if (enabled == null) {
      return details;
    }
    if (details != null) {
      if ((commandTarget == CommandTarget.INSTANCE && details.containsKey("controller"))
          || ((commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL)
          && details.containsKey("workloads"))) {
        return details;
      }
    }
    Map<String, Object> copy = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    if (commandTarget == CommandTarget.INSTANCE && isControllerCommand(cs)) {
      if (!copy.containsKey("controller")) {
        copy.put("controller", Map.of("enabled", enabled));
      }
    } else if (commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL) {
      if (!copy.containsKey("workloads")) {
        copy.put("workloads", Map.of("enabled", enabled));
      }
    }
    return copy.isEmpty() ? null : copy;
  }

  private void forwardToAll(ControlSignal cs, String payload) {
    String swarm = normaliseSwarmSegment(cs.swarmId());
    sendControl(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, "ALL", "ALL"), payload, "forward");
  }

  private void forwardToRole(ControlSignal cs, String payload, String role) {
    String swarm = normaliseSwarmSegment(cs.swarmId());
    sendControl(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, role, "ALL"), payload, "forward");
  }

  private void forwardToInstance(ControlSignal cs, String payload, TargetSpec spec) {
    String swarm = normaliseSwarmSegment(cs.swarmId());
    sendControl(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, spec.role(), spec.instance()), payload, "forward");
  }

  private CommandTarget effectiveCommandTarget(ControlSignal cs) {
    CommandTarget commandTarget = cs.commandTarget();
    if (commandTarget != null) {
      return commandTarget;
    }
    return CommandTarget.infer(cs.swarmId(), cs.role(), cs.instance(), cs.args());
  }

  private boolean isForLocalSwarm(ControlSignal cs) {
    return appliesToLocalSwarm(cs);
  }

  private String swarmIdOrDefault(ControlSignal cs) {
    String swarmId = cs.swarmId();
    if (swarmId == null || swarmId.isBlank()) {
      return swarmId;
    }
    return swarmId;
  }

  private String serializeArgs(ControlSignal cs) {
    Map<String, Object> args = cs.args();
    if (args == null || args.isEmpty()) {
      return "{}";
    }
    try {
      return mapper.writeValueAsString(args);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize control signal args", ex);
    }
  }

  private boolean appliesToLocalSwarm(ControlSignal cs) {
    String targetSwarm = cs.swarmId();
    if (targetSwarm == null || targetSwarm.isBlank()) {
      return true;
    }
    return swarmId.equalsIgnoreCase(targetSwarm);
  }

  private String resolveSignal(ControlSignalEnvelope envelope) {
    ControlSignal signal = envelope.signal();
    if (signal != null && signal.signal() != null && !signal.signal().isBlank()) {
      return signal.signal();
    }
    RoutingKey key = ControlPlaneRouting.parseSignal(envelope.routingKey());
    return key != null ? defaultSegment(key.type(), null) : null;
  }

  private boolean isControllerCommand(ControlSignal cs) {
    String role = cs.role();
    if (role == null || role.isBlank()) {
      return false;
    }
    if (!this.role.equalsIgnoreCase(role)) {
      return false;
    }
    String targetInstance = cs.instance();
    return targetInstance == null || targetInstance.isBlank() || instanceId.equals(targetInstance);
  }

  private TargetSpec resolveInstanceTarget(ControlSignal cs) {
    String role = cs.role();
    String instance = cs.instance();
    if (role == null || role.isBlank() || instance == null || instance.isBlank()) {
      return null;
    }
    return new TargetSpec(role, instance);
  }

  private String resolveRoleTarget(ControlSignal cs) {
    String role = cs.role();
    if (role == null || role.isBlank()) {
      return null;
    }
    return role;
  }

  private record PendingTemplate(ControlSignal signal, String resolvedSignal, String swarmIdFallback) {}

  private record PendingStart(ControlSignal signal, String resolvedSignal, String swarmIdFallback) {}

  private record TargetSpec(String role, String instance) {}

  private CommandState stateForError(String signal) {
    String state = stateFromLifecycle();
    if (state != null) {
      return new CommandState(state, null, null);
    }
    String effectiveSignal = signal == null ? "" : signal;
    return switch (effectiveSignal) {
      case "swarm-start" -> new CommandState("Stopped", null, null);
      case "swarm-stop" -> new CommandState("Running", null, null);
      case "swarm-remove" -> new CommandState("Stopped", null, null);
      case "swarm-template" -> new CommandState("Stopped", null, null);
      default -> null;
    };
  }

  private String stateFromLifecycle() {
    SwarmStatus status = lifecycle.getStatus();
    if (status == null) {
      return null;
    }
    return switch (status) {
      case READY -> "Ready";
      case RUNNING, STARTING -> "Running";
      case STOPPED, STOPPING, FAILED, NEW, CREATING -> "Stopped";
      case REMOVED, REMOVING -> "Removed";
    };
  }

  private String phaseForSignal(String signal) {
    if (signal == null || signal.isBlank()) {
      return signal;
    }
    return switch (signal) {
      case "swarm-template" -> "template";
      case "swarm-start" -> "start";
      case "swarm-stop" -> "stop";
      case "swarm-remove" -> "remove";
      case "config-update" -> "config-update";
      default -> signal;
    };
  }

  private String confirmationSignal(ControlSignal cs, String resolvedSignal) {
    return defaultSegment(cs != null ? cs.signal() : null, resolvedSignal);
  }

  private String requireSignal(String signal, String context) {
    if (signal == null || signal.isBlank()) {
      throw new IllegalArgumentException(context + " requires a resolved control signal");
    }
    return signal;
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to serialize confirmation", ex);
    }
  }

  private void sendStatusFull() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    SwarmStatus status = lifecycle.getStatus();
    boolean workloadsEnabled = workloadsEnabled(status);
    Map<String, QueueStats> queueSnapshot = lifecycle.snapshotQueueStats();
    String controlQueue = properties.controlQueueName(role, instanceId);
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String rk = ControlPlaneRouting.event("status-full", scope);
    StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .enabled(controllerEnabled)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("controllerEnabled", controllerEnabled)
        .data("workloadsEnabled", workloadsEnabled)
        .data("swarmDiagnostics", diagnostics.snapshot())
        .queueStats(toQueueStatsPayload(queueSnapshot))
        .controlIn(controlQueue)
        .controlRoutes(SwarmControllerRoutes.controllerControlRoutes(swarmId, role, instanceId))
        .controlOut(rk);
    appendTrafficPolicy(builder);
    String payload = builder.toJson();
    sendControl(rk, payload, "status");
  }

  private void sendStatusDelta() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    SwarmStatus status = lifecycle.getStatus();
    boolean workloadsEnabled = workloadsEnabled(status);
    Map<String, QueueStats> queueSnapshot = lifecycle.snapshotQueueStats();
    String controlQueue = properties.controlQueueName(role, instanceId);
    ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
    String rk = ControlPlaneRouting.event("status-delta", scope);
    StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .origin(instanceId)
        .swarmId(swarmId)
        .enabled(controllerEnabled)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("controllerEnabled", controllerEnabled)
        .data("workloadsEnabled", workloadsEnabled)
        .data("swarmDiagnostics", diagnostics.snapshot())
        .queueStats(toQueueStatsPayload(queueSnapshot))
        .controlIn(controlQueue)
        .controlRoutes(SwarmControllerRoutes.controllerControlRoutes(swarmId, role, instanceId))
        .controlOut(rk);
    appendTrafficPolicy(builder);
    String payload = builder.toJson();
    sendControl(rk, payload, "status");
  }

  private Map<String, Map<String, Object>> toQueueStatsPayload(Map<String, QueueStats> snapshot) {
    if (snapshot == null || snapshot.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<String, Object>> payload = new LinkedHashMap<>();
    for (Map.Entry<String, QueueStats> entry : snapshot.entrySet()) {
      String queueName = entry.getKey();
      QueueStats stats = entry.getValue();
      if (queueName == null || queueName.isBlank() || stats == null) {
        continue;
      }
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("depth", stats.depth());
      values.put("consumers", stats.consumers());
      if (stats.oldestAgeSec() != null && stats.oldestAgeSec().isPresent()) {
        values.put("oldestAgeSec", stats.oldestAgeSec().getAsLong());
      }
      payload.put(queueName, values);
    }
    return payload;
  }

  private void appendTrafficPolicy(StatusEnvelopeBuilder builder) {
    if (builder == null) {
      return;
    }
    TrafficPolicy policy = lifecycle.trafficPolicy();
    if (policy != null) {
      builder.data("trafficPolicy", policy);
    }
  }

  private String determineState(SwarmMetrics m) {
    if (m.desired() > 0 && m.healthy() == 0) {
      return "Unknown";
    }
    if (m.healthy() < m.desired()) {
      return "Degraded";
    }
    return lifecycle.getStatus().name();
  }

  private boolean workloadsEnabled(SwarmStatus status) {
    return status == SwarmStatus.RUNNING || status == SwarmStatus.STARTING;
  }

  private void sendControl(String routingKey, String payload, String context) {
    String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
    boolean statusLog = "status".equals(context) || (routingKey != null && routingKey.contains(".status-"));
    String snippet = snippet(payload);
    if (statusLog) {
      log.debug("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    }
    if (routingKey != null && routingKey.startsWith("sig.")) {
      controlPlane.publishSignal(new SignalMessage(routingKey, payload));
    } else {
      controlPlane.publishEvent(new EventMessage(routingKey, payload));
    }
  }

  private void sendControl(String routingKey, String payload) {
    sendControl(routingKey, payload, null);
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

  private MissingStatusSegment detectMissingStatusSegment(String routingKey) {
    if (routingKey == null) {
      return null;
    }
    String[] segments = routingKey.split("\\.", -1);
    if (segments.length < 5) {
      return null;
    }
    String roleSegment = segments[segments.length - 2];
    if (roleSegment == null || roleSegment.isBlank()) {
      return MissingStatusSegment.ROLE;
    }
    String instanceSegment = segments[segments.length - 1];
    if (instanceSegment == null || instanceSegment.isBlank()) {
      return MissingStatusSegment.INSTANCE;
    }
    return null;
  }

  private enum MissingStatusSegment {
    ROLE,
    INSTANCE
  }

  private String defaultSegment(String value, String fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return value.trim();
  }

  private boolean isAllSegment(String value) {
    return value != null && value.equalsIgnoreCase("ALL");
  }

  private String normaliseSwarmSegment(String value) {
    String resolved = defaultSegment(value, swarmId);
    if (isAllSegment(resolved)) {
      return swarmId;
    }
    return resolved;
  }

  private boolean isLocalSwarm(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return isAllSegment(value) || swarmId.equalsIgnoreCase(value);
  }
}
