package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final String ROLE = "swarm-controller";
  private final SwarmLifecycle lifecycle;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final ObjectMapper mapper;
  private final Map<CacheKey, CachedOutcome> outcomes;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private static final long MAX_STALENESS_MS = 15_000L;
  private volatile boolean controllerEnabled = true;

  @Autowired
  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper) {
    this(lifecycle, rabbit, instanceId, mapper, 100);
  }

  SwarmSignalListener(SwarmLifecycle lifecycle,
                      RabbitTemplate rabbit,
                      String instanceId,
                      ObjectMapper mapper,
                      int cacheSize) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.mapper = mapper.findAndRegisterModules();
    this.outcomes = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedOutcome> eldest) {
        return size() > cacheSize;
      }
    });
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
      if (routingKey.startsWith("sig.swarm-template.")) {
        String swarmId = routingKey.substring("sig.swarm-template.".length());
        if (Topology.SWARM_ID.equals(swarmId)) {
          processSwarmSignal(body, swarmId, node -> lifecycle.prepare(node.path("args").toString()), "template");
        }
      } else if (routingKey.startsWith("sig.swarm-start.")) {
        String swarmId = routingKey.substring("sig.swarm-start.".length());
        if (Topology.SWARM_ID.equals(swarmId)) {
          processSwarmSignal(body, swarmId, node -> {
            lifecycle.start(node.path("args").toString());
            sendStatusFull();
          }, "start");
        }
      } else if (routingKey.startsWith("sig.swarm-stop.")) {
        String swarmId = routingKey.substring("sig.swarm-stop.".length());
        if (Topology.SWARM_ID.equals(swarmId)) {
          processSwarmSignal(body, swarmId, node -> lifecycle.stop(), "stop");
        }
      } else if (routingKey.startsWith("sig.swarm-remove.")) {
        String swarmId = routingKey.substring("sig.swarm-remove.".length());
        if (Topology.SWARM_ID.equals(swarmId)) {
          processSwarmSignal(body, swarmId, node -> lifecycle.remove(), "remove");
        }
      } else if (routingKey.startsWith("sig.status-request")) {
        log.debug("Status request received: {}", routingKey);
        sendStatusFull();
      } else if (routingKey.startsWith("sig.config-update")) {
        processConfigUpdate(body, routingKey);
      } else if (routingKey.startsWith("ev.status-full.") || routingKey.startsWith("ev.status-delta.")) {
        String rest = routingKey.startsWith("ev.status-full.")
            ? routingKey.substring("ev.status-full.".length())
            : routingKey.substring("ev.status-delta.".length());
        String[] parts = rest.split(Pattern.quote("."), 2);
        if (parts.length == 2) {
          try {
            JsonNode node = mapper.readTree(body);
            String swarmId = node.path("swarmId").asText(null);
            if (swarmId != null && !swarmId.isBlank() && !Topology.SWARM_ID.equals(swarmId)) {
              log.debug("Ignoring status for swarm {} on routing key {}", swarmId, routingKey);
              return;
            }
            // We purposely count the controller's own status messages here. Keeping our heartbeat in the
            // lifecycle cache ensures the aggregate metrics still show a "degraded" swarm instead of
            // falling back to "unknown" when other roles go quiet.
            lifecycle.updateHeartbeat(parts[0], parts[1]);
            boolean enabled = node.path("data").path("enabled").asBoolean(true);
            lifecycle.updateEnabled(parts[0], parts[1], enabled);
            if (!enabled) {
              lifecycle.markReady(parts[0], parts[1]);
            }
          } catch (Exception e) {
            log.warn("status parse", e);
          }
        }
      }
    } finally {
      MDC.clear();
    }
  }

  private void processSwarmSignal(String body, String swarmId, SignalAction action, String label) {
    JsonNode node;
    ControlSignal cs;
    try {
      node = mapper.readTree(body);
      cs = parseControlSignal(node);
      MDC.put("correlation_id", cs.correlationId());
      MDC.put("idempotency_key", cs.idempotencyKey());
    } catch (Exception e) {
      log.warn(label + " parse", e);
      return;
    }
    CacheKey key = cacheKey(cs);
    CachedOutcome cached = outcomes.get(key);
    if (cached != null) {
      sendControl(cached.routingKey(), cached.payload(), "cached");
      emitDuplicate(cs, cached, swarmId);
      return;
    }
    try {
      log.info("{} signal for swarm {}", label.substring(0, 1).toUpperCase() + label.substring(1), swarmId);
      action.apply(node);
      CachedOutcome outcome = emitSuccess(cs, swarmId);
      if (outcome != null) outcomes.put(key, outcome);
    } catch (Exception e) {
      log.warn(label, e);
      CachedOutcome outcome = emitError(cs, e, swarmId);
      if (outcome != null) outcomes.put(key, outcome);
    }
  }

  private void processConfigUpdate(String body, String routingKey) {
    JsonNode node;
    ControlSignal cs;
    try {
      node = mapper.readTree(body);
      cs = parseControlSignal(node);
      MDC.put("correlation_id", cs.correlationId());
      MDC.put("idempotency_key", cs.idempotencyKey());
    } catch (Exception e) {
      log.warn("config parse", e);
      return;
    }

    if (!routingKey.startsWith("sig.config-update." + ROLE)) {
      log.debug("Ignoring config-update on routing key {}", routingKey);
      return;
    }
    CacheKey key = cacheKey(cs);
    CachedOutcome cached = outcomes.get(key);
    if (cached != null) {
      sendControl(cached.routingKey(), cached.payload(), "cached");
      emitDuplicate(cs, cached, null);
      return;
    }
    try {
      JsonNode dataNode = node.path("args").path("data");
      Boolean enabledFlag = dataNode.has("enabled") ? dataNode.path("enabled").asBoolean() : null;
      String originalTarget = cs.target();
      String normalizedTarget = normalizeTarget(originalTarget);
      CommandTarget commandTarget = cs.commandTarget();
      if (commandTarget == null) {
        commandTarget = CommandTarget.ALL;
      }

      Boolean stateEnabled = null;
      Map<String, Object> details = new LinkedHashMap<>();

      switch (commandTarget) {
        case SWARM -> {
          if (isSwarmToggle(normalizedTarget) && enabledFlag != null) {
            lifecycle.setSwarmEnabled(enabledFlag);
            sendStatusDelta();
            stateEnabled = enabledFlag;
            details.put("workloads", Map.of("enabled", enabledFlag));
          } else {
            forwardToAll(body);
          }
        }
        case INSTANCE -> {
          if (isControllerTarget(normalizedTarget, cs)) {
            if (enabledFlag != null) {
              controllerEnabled = enabledFlag;
              sendStatusDelta();
              stateEnabled = enabledFlag;
              details.put("controller", Map.of("enabled", enabledFlag));
            }
          } else {
            TargetSpec spec = resolveInstanceTarget(originalTarget);
            if (spec == null) {
              throw new IllegalArgumentException("commandTarget=instance requires role.instance target");
            }
            forwardToInstance(body, spec);
          }
        }
        case ROLE -> {
          if (normalizedTarget == null) {
            throw new IllegalArgumentException("commandTarget=role requires a role target");
          }
          forwardToRole(body, normalizedTarget);
        }
        case ALL -> forwardToAll(body);
      }

      CommandState state = configCommandState(cs, originalTarget != null ? originalTarget : normalizedTarget,
          stateEnabled, details);
      CachedOutcome outcome = emitSuccess(cs, null, state);
      if (outcome != null) outcomes.put(key, outcome);
    } catch (Exception e) {
      log.warn("config update", e);
      CachedOutcome outcome = emitError(cs, e, null);
      if (outcome != null) outcomes.put(key, outcome);
    }
  }

  private CacheKey cacheKey(ControlSignal cs) {
    String swarmId = cs.swarmId() != null ? cs.swarmId() : Topology.SWARM_ID;
    return new CacheKey(swarmId, cs.signal(), cs.role(), cs.instance(), cs.idempotencyKey());
  }

  @FunctionalInterface
  private interface SignalAction {
    void apply(JsonNode node) throws Exception;
  }

  private record CacheKey(String swarmId, String signal, String role, String instance, String idempotencyKey) {}

  private record CachedOutcome(String routingKey,
                               String payload,
                               String correlationId,
                               CommandState state) {}

  private record DuplicateNotice(Instant ts,
                                 String correlationId,
                                 String originalCorrelationId,
                                 String idempotencyKey,
                                 String signal,
                                 ConfirmationScope scope,
                                 CommandState state) {}

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    sendStatusDelta();
  }

  private ControlSignal parseControlSignal(JsonNode node) {
    String signal = node.path("signal").asText(null);
    String correlationId = node.path("correlationId").asText(null);
    String idempotencyKey = node.path("idempotencyKey").asText(null);
    String swarmId = node.path("swarmId").asText(null);
    String role = node.path("role").asText(null);
    String instance = node.path("instance").asText(null);

    JsonNode scope = node.path("scope");
    if (scope.isObject()) {
      if (swarmId == null && scope.hasNonNull("swarmId")) {
        swarmId = scope.path("swarmId").asText();
      }
      if (role == null && scope.hasNonNull("role")) {
        role = scope.path("role").asText();
      }
      if (instance == null && scope.hasNonNull("instance")) {
        instance = scope.path("instance").asText();
      }
    }

    Map<String, Object> args = null;
    JsonNode argsNode = node.get("args");
    if (argsNode != null && argsNode.isObject()) {
      args = mapper.convertValue(argsNode, new TypeReference<Map<String, Object>>() {});
    }

    String commandTargetValue = node.path("commandTarget").asText(null);
    CommandTarget commandTarget = null;
    if (commandTargetValue != null && !commandTargetValue.isBlank()) {
      try {
        commandTarget = CommandTarget.from(commandTargetValue);
      } catch (IllegalArgumentException ex) {
        log.warn("Unknown commandTarget {}", commandTargetValue);
      }
    }
    String target = node.path("target").asText(null);

    return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance, commandTarget, target, args);
  }

  private CachedOutcome emitSuccess(ControlSignal cs, String swarmIdFallback) {
    return emitSuccess(cs, swarmIdFallback, null);
  }

  private CachedOutcome emitSuccess(ControlSignal cs, String swarmIdFallback, CommandState overrideState) {
    String rk;
    if (cs.signal().startsWith("swarm-")) {
      String swarmId = cs.swarmId();
      if (swarmId == null) swarmId = swarmIdFallback;
      rk = "ev.ready." + cs.signal() + "." + swarmId;
    } else if ("config-update".equals(cs.signal()) && cs.role() != null && cs.instance() != null) {
      rk = "ev.ready.config-update." + cs.role() + "." + cs.instance();
    } else {
      return null;
    }
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    CommandState baseState = overrideState != null ? overrideState : stateForSuccess(cs);
    CommandState state = enrichState(cs, swarmIdFallback, baseState);
    ReadyConfirmation confirmation = new ReadyConfirmation(
        Instant.now(),
        cs.correlationId(),
        cs.idempotencyKey(),
        cs.signal(),
        scope,
        state
    );
    String json = toJson(confirmation);
    sendControl(rk, json, "ev.ready");
    return new CachedOutcome(rk, json, cs.correlationId(), state);
  }

  private CachedOutcome emitError(ControlSignal cs, Exception e, String swarmIdFallback) {
    String rk = "ev.error." + cs.signal();
    if (cs.swarmId() != null) {
      rk += "." + cs.swarmId();
    } else if (swarmIdFallback != null) {
      rk += "." + swarmIdFallback;
    } else if (cs.role() != null && cs.instance() != null) {
      rk += "." + cs.role() + "." + cs.instance();
    }
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    CommandState state = enrichState(cs, swarmIdFallback, stateForError(cs));
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
    sendControl(rk, json, "ev.error");
    return new CachedOutcome(rk, json, cs.correlationId(), state);
  }

  private void emitDuplicate(ControlSignal cs, CachedOutcome cached, String swarmIdFallback) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    CommandState state = cached != null && cached.state() != null
        ? cached.state()
        : enrichState(cs, swarmIdFallback, null);
    DuplicateNotice notice = new DuplicateNotice(
        Instant.now(),
        cs.correlationId(),
        cached.correlationId(),
        cs.idempotencyKey(),
        cs.signal(),
        scope,
        state
    );
    String rk = "ev.duplicate." + cs.signal();
    String json = toJson(notice);
    sendControl(rk, json, "duplicate");
  }

  private ConfirmationScope scopeFor(ControlSignal cs, String swarmIdFallback) {
    String swarmId = cs.swarmId();
    if (swarmId == null) {
      swarmId = swarmIdFallback;
    }
    if (swarmId == null || swarmId.isBlank()) {
      swarmId = Topology.SWARM_ID;
    }
    String role = cs.role();
    String instance = cs.instance();
    if ("config-update".equals(cs.signal())) {
      if (role == null || role.isBlank()) {
        role = ROLE;
      }
      if (instance == null || instance.isBlank()) {
        instance = this.instanceId;
      }
    }
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
    return new CommandState(status, null, cs.target(), null, null);
  }

  private CommandState configCommandState(ControlSignal cs,
                                          String target,
                                          Boolean enabled,
                                          Map<String, Object> details) {
    CommandState base = stateForSuccess(cs);
    Map<String, Object> detailCopy = (details == null || details.isEmpty()) ? null : new LinkedHashMap<>(details);
    return new CommandState(base != null ? base.status() : null, null, target, enabled, detailCopy);
  }

  private CommandState enrichState(ControlSignal cs, String swarmIdFallback, CommandState baseState) {
    CommandTarget commandTarget = cs.commandTarget();
    if (commandTarget == null) {
      commandTarget = CommandTarget.ALL;
    }
    String normalizedTarget = normalizeTarget(cs.target());
    ConfirmationScope stateScope = stateScopeForTarget(cs, swarmIdFallback, commandTarget, normalizedTarget);
    String resolvedTarget = resolveTargetValue(cs, baseState, stateScope);
    String status = baseState != null ? baseState.status() : null;
    Boolean enabled = baseState != null ? baseState.enabled() : null;
    Map<String, Object> details = baseState != null ? baseState.details() : null;
    Map<String, Object> mirroredDetails = mirrorEnablement(details, enabled, commandTarget, stateScope);
    return new CommandState(status, stateScope, resolvedTarget, enabled, mirroredDetails);
  }

  private ConfirmationScope stateScopeForTarget(ControlSignal cs,
                                                String swarmIdFallback,
                                                CommandTarget commandTarget,
                                                String normalizedTarget) {
    String swarmId = cs.swarmId();
    if (swarmId == null || swarmId.isBlank()) {
      swarmId = swarmIdFallback;
    }
    if (swarmId == null || swarmId.isBlank()) {
      swarmId = Topology.SWARM_ID;
    }
    return switch (commandTarget) {
      case ALL, SWARM -> new ConfirmationScope(swarmId, null, null);
      case ROLE -> new ConfirmationScope(swarmId, roleForState(cs, commandTarget, normalizedTarget), null);
      case INSTANCE -> {
        TargetSpec spec = resolveInstanceTarget(cs.target());
        if (spec != null) {
          yield new ConfirmationScope(swarmId, spec.role(), spec.instance());
        }
        if (isControllerTarget(normalizedTarget, cs)) {
          yield new ConfirmationScope(swarmId, ROLE, instanceId);
        }
        yield new ConfirmationScope(swarmId, roleForState(cs, commandTarget, normalizedTarget), null);
      }
    };
  }

  private String roleForState(ControlSignal cs, CommandTarget commandTarget, String normalizedTarget) {
    if (commandTarget == CommandTarget.INSTANCE) {
      TargetSpec spec = resolveInstanceTarget(cs.target());
      if (spec != null) {
        return spec.role();
      }
      if (isControllerTarget(normalizedTarget, cs)) {
        return ROLE;
      }
    }
    if (commandTarget == CommandTarget.ROLE) {
      String target = cs.target();
      if (target != null && !target.isBlank()) {
        return target.trim();
      }
    }
    String role = cs.role();
    if (role != null && !role.isBlank()) {
      return role;
    }
    return null;
  }

  private String resolveTargetValue(ControlSignal cs, CommandState baseState, ConfirmationScope stateScope) {
    if (baseState != null && baseState.target() != null) {
      return baseState.target();
    }
    String target = cs.target();
    if (target != null && !target.isBlank()) {
      return target;
    }
    if (stateScope != null) {
      if (stateScope.instance() != null) {
        return stateScope.instance();
      }
      if (stateScope.role() != null) {
        return stateScope.role();
      }
      if (stateScope.swarmId() != null) {
        return stateScope.swarmId();
      }
    }
    return null;
  }

  private Map<String, Object> mirrorEnablement(Map<String, Object> details,
                                               Boolean enabled,
                                               CommandTarget commandTarget,
                                               ConfirmationScope stateScope) {
    if (enabled == null) {
      return details;
    }
    if (details != null) {
      if ((commandTarget == CommandTarget.INSTANCE && details.containsKey("controller"))
          || ((commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL)
          && details.containsKey("workloads"))
          || details.containsKey("target")) {
        return details;
      }
    }
    Map<String, Object> copy = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
    if (commandTarget == CommandTarget.INSTANCE && stateScope != null && ROLE.equals(stateScope.role())) {
      copy.put("controller", Map.of("enabled", enabled));
    } else if (commandTarget == CommandTarget.SWARM || commandTarget == CommandTarget.ALL) {
      copy.put("workloads", Map.of("enabled", enabled));
    } else {
      copy.put("target", Map.of("enabled", enabled));
    }
    return copy.isEmpty() ? null : copy;
  }

  private void forwardToAll(String payload) {
    sendControl("sig.config-update", payload, "forward");
  }

  private void forwardToRole(String payload, String role) {
    sendControl("sig.config-update." + role, payload, "forward");
  }

  private void forwardToInstance(String payload, TargetSpec spec) {
    sendControl("sig.config-update." + spec.role() + "." + spec.instance(), payload, "forward");
  }

  private boolean isSwarmToggle(String normalizedTarget) {
    return normalizedTarget == null || normalizedTarget.equals("swarm") || normalizedTarget.equals(Topology.SWARM_ID.toLowerCase(Locale.ROOT));
  }

  private boolean isControllerTarget(String normalizedTarget, ControlSignal cs) {
    if (normalizedTarget == null || normalizedTarget.isBlank()) {
      return true;
    }
    if ("controller".equals(normalizedTarget)) {
      return true;
    }
    if (instanceId.equalsIgnoreCase(normalizedTarget)) {
      return true;
    }
    String csInstance = cs.instance();
    return csInstance != null && csInstance.equalsIgnoreCase(normalizedTarget);
  }

  private String normalizeTarget(String target) {
    if (target == null) {
      return null;
    }
    String trimmed = target.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private TargetSpec resolveInstanceTarget(String target) {
    if (target == null || target.isBlank()) {
      return null;
    }
    String trimmed = target.trim();
    String[] parts;
    if (trimmed.contains(".")) {
      parts = trimmed.split("\\.", 2);
    } else if (trimmed.contains(":")) {
      parts = trimmed.split(":", 2);
    } else {
      return null;
    }
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      return null;
    }
    return new TargetSpec(parts[0], parts[1]);
  }

  private record TargetSpec(String role, String instance) {}

  private CommandState stateForError(ControlSignal cs) {
    String state = stateFromLifecycle();
    if (state != null) {
      return new CommandState(state, null, cs.target(), null, null);
    }
    return switch (cs.signal()) {
      case "swarm-start" -> new CommandState("Stopped", null, cs.target(), null, null);
      case "swarm-stop" -> new CommandState("Running", null, cs.target(), null, null);
      case "swarm-remove" -> new CommandState("Stopped", null, cs.target(), null, null);
      case "swarm-template" -> new CommandState("Stopped", null, cs.target(), null, null);
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
    String rk = "ev.status-full." + ROLE + "." + instanceId;
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
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-template." + Topology.SWARM_ID,
            "sig.swarm-start." + Topology.SWARM_ID,
            "sig.swarm-stop." + Topology.SWARM_ID,
            "sig.swarm-remove." + Topology.SWARM_ID)
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
    String rk = "ev.status-delta." + ROLE + "." + instanceId;
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
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-template." + Topology.SWARM_ID,
            "sig.swarm-start." + Topology.SWARM_ID,
            "sig.swarm-stop." + Topology.SWARM_ID,
            "sig.swarm-remove." + Topology.SWARM_ID)
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

  private void sendControl(String routingKey, String payload, String context) {
    String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
    boolean statusLog = "status".equals(context) || (routingKey != null && routingKey.contains(".status-"));
    String snippet = snippet(payload);
    if (statusLog) {
      log.debug("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] {} rk={} inst={} payload={}", label, routingKey, instanceId, snippet);
    }
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload);
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
}
