package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.swarmcontroller.SwarmStatus;
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
import java.util.regex.Pattern;
import java.util.HashMap;
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
  private static final long STATUS_INTERVAL_MS = 5000L;

  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId,
                             ObjectMapper mapper) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.mapper = mapper;
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
    if (routingKey.startsWith("sig.swarm-template.")) {
      String swarmId = routingKey.substring("sig.swarm-template.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        try {
          var node = mapper.readTree(body);
          ControlSignal cs = parseControlSignal(node);
          log.info("Template signal for swarm {}", swarmId);
          lifecycle.prepare(node.path("args").toString());
          emitSuccess(cs);
        } catch (Exception e) {
          log.warn("template", e);
          try {
            var node = mapper.readTree(body);
            ControlSignal cs = parseControlSignal(node);
            emitError(cs, e);
          } catch (Exception ex) {
            log.warn("error emit", ex);
          }
        }
      }
    } else if (routingKey.startsWith("sig.swarm-start.")) {
      String swarmId = routingKey.substring("sig.swarm-start.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        try {
          var node = mapper.readTree(body);
          ControlSignal cs = parseControlSignal(node);
          log.info("Start signal for swarm {}", swarmId);
          lifecycle.start(node.path("args").toString());
          sendStatusFull();
          emitSuccess(cs);
        } catch (Exception e) {
          log.warn("start", e);
          try {
            var node = mapper.readTree(body);
            ControlSignal cs = parseControlSignal(node);
            emitError(cs, e);
          } catch (Exception ex) {
            log.warn("error emit", ex);
          }
        }
      }
    } else if (routingKey.startsWith("sig.scenario-part.")) {
      String swarmId = routingKey.substring("sig.scenario-part.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        log.info("Scenario part for swarm {}", swarmId);
        lifecycle.applyScenarioStep(body);
      }
    } else if (routingKey.startsWith("sig.scenario-start.")) {
      String swarmId = routingKey.substring("sig.scenario-start.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        log.info("Scenario start for swarm {}", swarmId);
        lifecycle.enableAll();
        sendStatusFull();
      }
    } else if (routingKey.startsWith("sig.swarm-stop.")) {
      String swarmId = routingKey.substring("sig.swarm-stop.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        try {
          var node = mapper.readTree(body);
          ControlSignal cs = parseControlSignal(node);
          log.info("Stop signal for swarm {}", swarmId);
          lifecycle.stop();
          emitSuccess(cs);
        } catch (Exception e) {
          log.warn("stop", e);
          try {
            var node = mapper.readTree(body);
            ControlSignal cs = parseControlSignal(node);
            emitError(cs, e);
          } catch (Exception ex) {
            log.warn("error emit", ex);
          }
        }
      }
    } else if (routingKey.startsWith("sig.swarm-remove.")) {
      String swarmId = routingKey.substring("sig.swarm-remove.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        try {
          var node = mapper.readTree(body);
          ControlSignal cs = parseControlSignal(node);
          log.info("Remove signal for swarm {}", swarmId);
          lifecycle.remove();
          emitSuccess(cs);
        } catch (Exception e) {
          log.warn("remove", e);
          try {
            var node = mapper.readTree(body);
            ControlSignal cs = parseControlSignal(node);
            emitError(cs, e);
          } catch (Exception ex) {
            log.warn("error emit", ex);
          }
        }
      }
    } else if (routingKey.startsWith("sig.status-request")) {
      log.info("Status request received: {}", routingKey);
      sendStatusFull();
    } else if (routingKey.startsWith("sig.config-update")) {
      log.info("Config update received: {} payload={} ", routingKey, body);
      try {
        JsonNode node = mapper.readTree(body);
        ControlSignal cs = parseControlSignal(node);
        JsonNode enabledNode = node.path("args").path("data").path("enabled");
        if (enabledNode.isBoolean() && !enabledNode.asBoolean()) {
          log.warn("Ignoring attempt to disable swarm-controller");
        }
        emitSuccess(cs);
      } catch (Exception e) {
        log.warn("config parse", e);
        try {
          var node = mapper.readTree(body);
          ControlSignal cs = parseControlSignal(node);
          emitError(cs, e);
        } catch (Exception ex) {
          log.warn("error emit", ex);
        }
      }
    } else if (routingKey.startsWith("ev.ready.")) {
      String rest = routingKey.substring("ev.ready.".length());
      String[] parts = rest.split(Pattern.quote("."), 2);
      if (parts.length == 2) {
        try {
          JsonNode node = mapper.readTree(body);
          boolean enabled = node.path("data").path("enabled").asBoolean(true);
          if (!enabled) {
            lifecycle.markReady(parts[0], parts[1]);
          }
        } catch (Exception e) {
          log.warn("ready parse", e);
        }
      }
    }
    MDC.clear();
  }

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
    return new ControlSignal(
        node.path("correlationId").asText(null),
        node.path("idempotencyKey").asText(null),
        node.path("signal").asText(null),
        scope);
  }

  private void emitSuccess(ControlSignal cs) {
    String rk;
    if (cs.signal().startsWith("swarm-")) {
      rk = "ev.ready." + cs.signal() + "." + cs.scope().get("swarmId");
    } else if ("config-update".equals(cs.signal())) {
      rk = "ev.ready.config-update." + cs.scope().get("role") + "." + cs.scope().get("instance");
    } else {
      return;
    }
    var payload = mapper.createObjectNode();
    payload.put("signal", cs.signal());
    payload.put("result", "success");
    payload.set("scope", mapper.valueToTree(cs.scope()));
    payload.put("idempotencyKey", cs.idempotencyKey());
    payload.put("correlationId", cs.correlationId());
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload.toString());
  }

  private void emitError(ControlSignal cs, Exception e) {
    String rk = "ev.error." + cs.signal();
    if (cs.scope().containsKey("swarmId")) {
      rk += "." + cs.scope().get("swarmId");
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
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload.toString());
  }

  private void sendStatusFull() {
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String rk = "ev.status-full." + ROLE + "." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(true)
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
            "sig.scenario-part.*",
            "sig.scenario-start.*",
            "sig.swarm-stop.*",
            "sig.swarm-remove.*")
        .controlOut(rk)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private void sendStatusDelta() {
    String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
    String rk = "ev.status-delta." + ROLE + "." + instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(ROLE)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .enabled(true)
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
            "sig.scenario-part.*",
            "sig.scenario-start.*",
            "sig.swarm-stop.*",
            "sig.swarm-remove.*")
        .controlOut(rk)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
}
