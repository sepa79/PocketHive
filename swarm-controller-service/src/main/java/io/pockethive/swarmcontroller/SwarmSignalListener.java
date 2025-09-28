package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandState;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.swarmcontroller.SwarmMetrics;
import io.pockethive.swarmcontroller.SwarmStatus;
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

@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final String ROLE = "swarm-controller";
  private final SwarmLifecycle lifecycle;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final ObjectMapper mapper;
  private final ManagerControlPlane controlPlane;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final long MAX_STALENESS_MS = 15_000L;
  private volatile boolean controllerEnabled = false;

  @Autowired
  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.controlPlane = ManagerControlPlane.builder(
        new AmqpControlPlanePublisher(rabbit, Topology.CONTROL_EXCHANGE),
        this.mapper)
        .identity(new ControlPlaneIdentity(Topology.SWARM_ID, ROLE, instanceId))
        .duplicateCache(java.time.Duration.ofMinutes(1), 256)
        .build();
    try {
      sendStatusFull();
    } catch (Exception e) {
      log.warn("initial status", e);
    }
  }

  @RabbitListener(queues = "#{controlQueue.name}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    if (routingKey == null) return;
    MDC.put("swarm_id", Topology.SWARM_ID);
    MDC.put("service", ROLE);
    MDC.put("instance", instanceId);
    String snippet = snippet(body);
    if (routingKey.startsWith("ev.status-") || routingKey.startsWith("sig.status-request")) {
      log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    }
    try {
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
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleStatusEvent(String routingKey, String body) {
    RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
    if (eventKey == null) {
      return;
    }
    if (!isLocalSwarm(eventKey.swarmId())) {
      log.debug("Ignoring status for swarm {} on routing key {}", eventKey.swarmId(), routingKey);
      return;
    }
    String role = eventKey.role();
    String instance = eventKey.instance();
    if (role == null || instance == null) {
      return;
    }
    try {
      JsonNode node = mapper.readTree(body);
      String payloadSwarm = node.path("swarmId").asText(null);
      if (payloadSwarm != null && !payloadSwarm.isBlank() && !Topology.SWARM_ID.equals(payloadSwarm)) {
        log.debug("Ignoring status payload for swarm {} on routing key {}", payloadSwarm, routingKey);
        return;
      }
      lifecycle.updateHeartbeat(role, instance);
      boolean enabled = node.path("data").path("enabled").asBoolean(true);
      lifecycle.updateEnabled(role, instance, enabled);
      if (!enabled) {
        lifecycle.markReady(role, instance);
      }
    } catch (Exception e) {
      log.warn("status parse", e);
    }
  }

  private void processSwarmSignal(ControlSignal cs, String swarmId, SignalAction action, String label) {
    MDC.put("correlation_id", cs.correlationId());
    MDC.put("idempotency_key", cs.idempotencyKey());
    try {
      log.info("{} signal for swarm {}", label.substring(0, 1).toUpperCase() + label.substring(1), swarmId);
      action.apply(serializeArgs(cs));
      emitSuccess(cs, swarmId);
    } catch (Exception e) {
      log.warn(label, e);
      emitError(cs, e, swarmId);
    }
  }

  private void processConfigUpdate(ControlSignalEnvelope envelope, String rawPayload) {
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

      switch (commandTarget) {
        case SWARM -> {
          if (enabledFlag != null && appliesToLocalSwarm(cs)) {
            lifecycle.setSwarmEnabled(enabledFlag);
            controllerEnabled = enabledFlag;
            sendStatusDelta();
            stateEnabled = enabledFlag;
            details.put("workloads", Map.of("enabled", enabledFlag));
          } else {
            fanouts.add(() -> forwardToAll(cs, rawPayload));
          }
        }
        case INSTANCE -> {
          if (isControllerCommand(cs)) {
            if (enabledFlag != null) {
              controllerEnabled = enabledFlag;
              sendStatusDelta();
              stateEnabled = enabledFlag;
              details.put("controller", Map.of("enabled", enabledFlag));
            }
          } else {
            TargetSpec spec = resolveInstanceTarget(cs);
            if (spec == null) {
              throw new IllegalArgumentException("commandTarget=instance requires role and instance fields");
            }
            fanouts.add(() -> forwardToInstance(cs, rawPayload, spec));
          }
        }
        case ROLE -> {
          String roleTarget = resolveRoleTarget(cs);
          if (roleTarget == null) {
            throw new IllegalArgumentException("commandTarget=role requires a role field");
          }
          fanouts.add(() -> forwardToRole(cs, rawPayload, roleTarget));
        }
        case ALL -> fanouts.add(() -> forwardToAll(cs, rawPayload));
      }

      CommandState state = configCommandState(cs, stateEnabled, details);
      fanouts.forEach(Runnable::run);
      emitSuccess(cs, null, state);
    } catch (Exception e) {
      log.warn("config update", e);
      emitError(cs, e, null);
    }
  }

  private void handleSignal(ControlSignalEnvelope envelope, String rawPayload) {
    ControlSignal cs = envelope.signal();
    if (cs == null) {
      return;
    }
    String signal = resolveSignal(envelope);
    switch (signal) {
      case "swarm-template" -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, swarmIdOrDefault(cs), args -> lifecycle.prepare(args), "template");
        }
      }
      case "swarm-start" -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, swarmIdOrDefault(cs), args -> {
            lifecycle.start(args);
            sendStatusFull();
          }, "start");
        }
      }
      case "swarm-stop" -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, swarmIdOrDefault(cs), args -> lifecycle.stop(), "stop");
        }
      }
      case "swarm-remove" -> {
        if (isForLocalSwarm(cs)) {
          processSwarmSignal(cs, swarmIdOrDefault(cs), args -> lifecycle.remove(), "remove");
        }
      }
      case "status-request" -> {
        log.debug("Status request received: {}", envelope.routingKey());
        sendStatusFull();
      }
      case "config-update" -> processConfigUpdate(envelope, rawPayload);
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
    String roleSegment = defaultSegment(key.role(), ROLE);
    if (!ROLE.equalsIgnoreCase(roleSegment) && !isAllSegment(roleSegment)) {
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
    String roleSegment = defaultSegment(key.role(), ROLE);
    if (!ROLE.equalsIgnoreCase(roleSegment) && !isAllSegment(roleSegment)) {
      return false;
    }
    if (isAllSegment(roleSegment)
        && commandTarget == CommandTarget.SWARM
        && (cs.role() == null || cs.role().isBlank() || isAllSegment(cs.role()))) {
      return false;
    }
    String targetInstance = key.instance();
    if (isAllSegment(targetInstance)) {
      return commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL;
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
  }

  private void emitSuccess(ControlSignal cs, String swarmIdFallback) {
    emitSuccess(cs, swarmIdFallback, null);
  }

  private void emitSuccess(ControlSignal cs, String swarmIdFallback, CommandState overrideState) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    CommandState baseState = overrideState != null ? overrideState : stateForSuccess(cs);
    CommandState state = enrichState(cs, baseState);
    String type = successEventType(cs.signal());
    if (type == null) {
      return;
    }
    ReadyConfirmation confirmation = new ReadyConfirmation(
        Instant.now(),
        cs.correlationId(),
        cs.idempotencyKey(),
        cs.signal(),
        scope,
        state
    );
    String json = toJson(confirmation);
    sendControl(ControlPlaneRouting.event(type, scope), json, "ev.ready");
  }

  private void emitError(ControlSignal cs, Exception e, String swarmIdFallback) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    CommandState state = enrichState(cs, stateForError(cs));
    String type = errorEventType(cs.signal());
    ErrorConfirmation confirmation = new ErrorConfirmation(
        Instant.now(),
        cs.correlationId(),
        cs.idempotencyKey(),
        cs.signal(),
        scope,
        state,
        phaseForSignal(cs.signal()),
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
    swarmId = defaultSegment(swarmId, Topology.SWARM_ID);
    String role = defaultSegment(cs.role(), ROLE);
    String instance = defaultSegment(cs.instance(), instanceId);
    return new ConfirmationScope(swarmId, role, instance);
  }

  private CommandState stateForSuccess(ControlSignal cs) {
    String status = switch (cs.signal()) {
      case "swarm-template" -> "Ready";
      case "swarm-start" -> "Running";
      case "swarm-stop" -> "Stopped";
      case "swarm-remove" -> "Removed";
      case "config-update" -> stateFromLifecycle();
      default -> stateFromLifecycle();
    };
    return new CommandState(status, null, null);
  }

  private String successEventType(String signal) {
    if (signal == null || signal.isBlank()) {
      return null;
    }
    if (signal.startsWith("swarm-")) {
      return "ready." + signal;
    }
    if ("config-update".equals(signal)) {
      return "ready.config-update";
    }
    return "ready." + signal;
  }

  private String errorEventType(String signal) {
    if (signal == null || signal.isBlank()) {
      return "error";
    }
    if (signal.startsWith("swarm-")) {
      return "error." + signal;
    }
    if ("config-update".equals(signal)) {
      return "error.config-update";
    }
    return "error." + signal;
  }

  private CommandState configCommandState(ControlSignal cs,
                                          Boolean enabled,
                                          Map<String, Object> details) {
    CommandState base = stateForSuccess(cs);
    Map<String, Object> detailCopy = (details == null || details.isEmpty()) ? null : new LinkedHashMap<>(details);
    return new CommandState(base != null ? base.status() : null, enabled, detailCopy);
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
    sendControl(ControlPlaneRouting.signal("config-update", swarm, "ALL", "ALL"), payload, "forward");
  }

  private void forwardToRole(ControlSignal cs, String payload, String role) {
    String swarm = normaliseSwarmSegment(cs.swarmId());
    sendControl(ControlPlaneRouting.signal("config-update", swarm, role, "ALL"), payload, "forward");
  }

  private void forwardToInstance(ControlSignal cs, String payload, TargetSpec spec) {
    String swarm = normaliseSwarmSegment(cs.swarmId());
    sendControl(ControlPlaneRouting.signal("config-update", swarm, spec.role(), spec.instance()), payload, "forward");
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
      return Topology.SWARM_ID;
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
    return Topology.SWARM_ID.equalsIgnoreCase(targetSwarm);
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
    if (!ROLE.equalsIgnoreCase(role)) {
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

  private record TargetSpec(String role, String instance) {}

  private CommandState stateForError(ControlSignal cs) {
    String state = stateFromLifecycle();
    if (state != null) {
      return new CommandState(state, null, null);
    }
    return switch (cs.signal()) {
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
    return switch (signal) {
      case "swarm-template" -> "template";
      case "swarm-start" -> "start";
      case "swarm-stop" -> "stop";
      case "swarm-remove" -> "remove";
      case "config-update" -> "config-update";
      default -> signal;
    };
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
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    ConfirmationScope scope = ConfirmationScope.forInstance(Topology.SWARM_ID, ROLE, instanceId);
    String rk = ControlPlaneRouting.event("status-full", scope);
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(controllerEnabled)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("controllerEnabled", controllerEnabled)
        .data("workloadsEnabled", workloadsEnabled)
        .controlIn(controlQueue)
        .controlRoutes(controllerControlRoutes())
        .controlOut(rk)
        .toJson();
    sendControl(rk, payload, "status");
  }

  private void sendStatusDelta() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    SwarmStatus status = lifecycle.getStatus();
    boolean workloadsEnabled = workloadsEnabled(status);
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    ConfirmationScope scope = ConfirmationScope.forInstance(Topology.SWARM_ID, ROLE, instanceId);
    String rk = ControlPlaneRouting.event("status-delta", scope);
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(controllerEnabled)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", status.name())
        .data("controllerEnabled", controllerEnabled)
        .data("workloadsEnabled", workloadsEnabled)
        .controlIn(controlQueue)
        .controlRoutes(controllerControlRoutes())
        .controlOut(rk)
        .toJson();
    sendControl(rk, payload, "status");
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

  private String[] controllerControlRoutes() {
    String swarm = Topology.SWARM_ID;
    return new String[] {
        ControlPlaneRouting.signal("config-update", "ALL", ROLE, "ALL"),
        ControlPlaneRouting.signal("config-update", swarm, ROLE, "ALL"),
        ControlPlaneRouting.signal("config-update", swarm, ROLE, instanceId),
        ControlPlaneRouting.signal("config-update", swarm, "ALL", "ALL"),
        ControlPlaneRouting.signal("status-request", "ALL", ROLE, "ALL"),
        ControlPlaneRouting.signal("status-request", swarm, ROLE, "ALL"),
        ControlPlaneRouting.signal("status-request", swarm, ROLE, instanceId),
        ControlPlaneRouting.signal("swarm-template", swarm, ROLE, "ALL"),
        ControlPlaneRouting.signal("swarm-start", swarm, ROLE, "ALL"),
        ControlPlaneRouting.signal("swarm-stop", swarm, ROLE, "ALL"),
        ControlPlaneRouting.signal("swarm-remove", swarm, ROLE, "ALL")
    };
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
    String resolved = defaultSegment(value, Topology.SWARM_ID);
    if (isAllSegment(resolved)) {
      return Topology.SWARM_ID;
    }
    return resolved;
  }

  private boolean isLocalSwarm(String value) {
    if (value == null || value.isBlank()) {
      return true;
    }
    return isAllSegment(value) || Topology.SWARM_ID.equalsIgnoreCase(value);
  }
}
