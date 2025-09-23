package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        log.info("Config update received: {} payload={} ", routingKey, body);
        processConfigUpdate(body);
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

  private void processConfigUpdate(String body) {
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
    CacheKey key = cacheKey(cs);
    CachedOutcome cached = outcomes.get(key);
    if (cached != null) {
      sendControl(cached.routingKey(), cached.payload(), "cached");
      emitDuplicate(cs, cached, null);
      return;
    }
    try {
      JsonNode dataNode = node.path("args").path("data");
      boolean enabled = dataNode.path("enabled").asBoolean(true);
      String target = dataNode.path("target").asText("controller");
      if ("swarm".equalsIgnoreCase(target)) {
        lifecycle.setSwarmEnabled(enabled);
        sendStatusDelta();
      } else if ("controller".equalsIgnoreCase(target)) {
        controllerEnabled = enabled;
        sendStatusDelta();
      } else {
        log.warn("Unknown config-update target: {}", target);
      }
      CachedOutcome outcome = emitSuccess(cs, null);
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

  private record CachedOutcome(String routingKey, String payload, String correlationId) {}

  private record DuplicateNotice(Instant ts,
                                 String correlationId,
                                 String originalCorrelationId,
                                 String idempotencyKey,
                                 String signal,
                                 ConfirmationScope scope) {}

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

    return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance, args);
  }

  private CachedOutcome emitSuccess(ControlSignal cs, String swarmIdFallback) {
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
    String state = stateForSuccess(cs);
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
    return new CachedOutcome(rk, json, cs.correlationId());
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
    String state = stateForError(cs);
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
    return new CachedOutcome(rk, json, cs.correlationId());
  }

  private void emitDuplicate(ControlSignal cs, CachedOutcome cached, String swarmIdFallback) {
    ConfirmationScope scope = scopeFor(cs, swarmIdFallback);
    DuplicateNotice notice = new DuplicateNotice(
        Instant.now(),
        cs.correlationId(),
        cached.correlationId(),
        cs.idempotencyKey(),
        cs.signal(),
        scope
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

  private String stateForSuccess(ControlSignal cs) {
    return switch (cs.signal()) {
      case "swarm-template" -> "Ready";
      case "swarm-start" -> "Running";
      case "swarm-stop" -> "Stopped";
      case "swarm-remove" -> "Removed";
      case "config-update" -> stateFromLifecycle();
      default -> stateFromLifecycle();
    };
  }

  private String stateForError(ControlSignal cs) {
    String state = stateFromLifecycle();
    if (state != null) {
      return state;
    }
    return switch (cs.signal()) {
      case "swarm-start" -> "Stopped";
      case "swarm-stop" -> "Running";
      case "swarm-remove" -> "Stopped";
      case "swarm-template" -> "Stopped";
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
