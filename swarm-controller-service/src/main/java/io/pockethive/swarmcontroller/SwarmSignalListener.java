package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.swarmcontroller.SwarmStatus;
import io.pockethive.swarmcontroller.SwarmMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
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
    this.mapper = mapper;
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
    log.info("received {} : {}", routingKey, body);
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
        log.info("Status request received: {}", routingKey);
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
          lifecycle.updateHeartbeat(parts[0], parts[1]);
          try {
            JsonNode node = mapper.readTree(body);
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
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, cached.routingKey(), cached.payload());
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
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, cached.routingKey(), cached.payload());
      return;
    }
    try {
      JsonNode enabledNode = node.path("args").path("data").path("enabled");
      if (enabledNode.isBoolean() && !enabledNode.asBoolean()) {
        log.warn("Ignoring attempt to disable swarm-controller");
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
    String swarmId = cs.scope().getOrDefault("swarmId", Topology.SWARM_ID);
    String role = cs.scope().get("role");
    String instance = cs.scope().get("instance");
    return new CacheKey(swarmId, cs.signal(), role, instance, cs.idempotencyKey());
  }

  @FunctionalInterface
  private interface SignalAction {
    void apply(JsonNode node) throws Exception;
  }

  private record CacheKey(String swarmId, String signal, String role, String instance, String idempotencyKey) {}

  private record CachedOutcome(String routingKey, String payload) {}

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    sendStatusDelta();
  }

  private ControlSignal parseControlSignal(JsonNode node) {
    Map<String, String> scope = new HashMap<>();
    JsonNode s = node.path("scope");
    if (s.isObject()) {
      s.fields().forEachRemaining(e -> scope.put(e.getKey(), e.getValue().asText()));
    }
    if (!scope.containsKey("swarmId") && node.hasNonNull("swarmId")) {
      scope.put("swarmId", node.path("swarmId").asText());
    }
    return new ControlSignal(
        node.path("correlationId").asText(null),
        node.path("idempotencyKey").asText(null),
        node.path("signal").asText(null),
        scope);
  }

  private CachedOutcome emitSuccess(ControlSignal cs, String swarmIdFallback) {
    String rk;
    if (cs.signal().startsWith("swarm-")) {
      String swarmId = cs.scope().get("swarmId");
      if (swarmId == null) swarmId = swarmIdFallback;
      rk = "ev.ready." + cs.signal() + "." + swarmId;
    } else if ("config-update".equals(cs.signal())) {
      rk = "ev.ready.config-update." + cs.scope().get("role") + "." + cs.scope().get("instance");
    } else {
      return null;
    }
    var payload = mapper.createObjectNode();
    payload.put("signal", cs.signal());
    payload.put("result", "success");
    payload.set("scope", mapper.valueToTree(cs.scope()));
    payload.put("idempotencyKey", cs.idempotencyKey());
    payload.put("correlationId", cs.correlationId());
    String json = payload.toString();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    return new CachedOutcome(rk, json);
  }

  private CachedOutcome emitError(ControlSignal cs, Exception e, String swarmIdFallback) {
    String rk = "ev.error." + cs.signal();
    if (cs.scope().containsKey("swarmId")) {
      rk += "." + cs.scope().get("swarmId");
    } else if (swarmIdFallback != null) {
      rk += "." + swarmIdFallback;
    } else if (cs.scope().containsKey("role") && cs.scope().containsKey("instance")) {
      rk += "." + cs.scope().get("role") + "." + cs.scope().get("instance");
    }
    var payload = mapper.createObjectNode();
    payload.put("signal", cs.signal());
    payload.put("result", "error");
    payload.set("scope", mapper.valueToTree(cs.scope()));
    payload.put("idempotencyKey", cs.idempotencyKey());
    payload.put("correlationId", cs.correlationId());
    payload.put("code", e.getClass().getSimpleName());
    payload.put("message", e.getMessage());
    String json = payload.toString();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    return new CachedOutcome(rk, json);
  }

  private void sendStatusFull() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String rk = "ev.status-full." + ROLE + "." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(true)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", lifecycle.getStatus().name())
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-template.*",
            "sig.swarm-start.*",
            "sig.swarm-stop.*",
            "sig.swarm-remove.*")
        .controlOut(rk)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private void sendStatusDelta() {
    SwarmMetrics m = lifecycle.getMetrics();
    String state = determineState(m);
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String rk = "ev.status-delta." + ROLE + "." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(true)
        .state(state)
        .watermark(m.watermark())
        .maxStalenessSec(MAX_STALENESS_MS / 1000)
        .totals(m.desired(), m.healthy(), m.running(), m.enabled())
        .data("swarmStatus", lifecycle.getStatus().name())
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-template.*",
            "sig.swarm-start.*",
            "sig.swarm-stop.*",
            "sig.swarm-remove.*")
        .controlOut(rk)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
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
}
