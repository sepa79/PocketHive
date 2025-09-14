package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlSignal;
import io.pockethive.controlplane.Confirmation;
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
  private final java.util.Map<String, Confirmation> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
  private String pendingTemplateKey;
  private String pendingTemplateCorrelation;
  private String pendingTemplateIdempotency;

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
    if (routingKey.startsWith("sig.")) {
      try {
        ControlSignal sig = mapper.readValue(body, ControlSignal.class);
        String key = sig.signal() + ":" + (sig.swarmId() == null ? "" : sig.swarmId()) + ":" + sig.idempotencyKey();
        Confirmation prior = outcomes.get(key);
        if (prior != null) {
          sendConfirmation(prior);
          MDC.clear();
          return;
        }
        switch (sig.signal()) {
          case "swarm-template" -> {
            if (Topology.SWARM_ID.equals(sig.swarmId())) {
              log.info("Template signal for swarm {}", sig.swarmId());
              lifecycle.prepare(sig.payload() != null ? mapper.writeValueAsString(sig.payload()) : "",
                  sig.correlationId(), sig.idempotencyKey());
              pendingTemplateKey = key;
              pendingTemplateCorrelation = sig.correlationId();
              pendingTemplateIdempotency = sig.idempotencyKey();
            }
          }
          case "swarm-start" -> {
            if (Topology.SWARM_ID.equals(sig.swarmId())) {
              log.info("Start signal for swarm {}", sig.swarmId());
              lifecycle.start(sig.payload() != null ? mapper.writeValueAsString(sig.payload()) : "",
                  sig.correlationId(), sig.idempotencyKey());
              Confirmation conf = Confirmation.success("swarm-start", sig.swarmId(), null, null,
                  sig.correlationId(), sig.idempotencyKey(), "Running", null);
              outcomes.put(key, conf);
              sendConfirmation(conf);
              sendStatusFull();
            }
          }
          case "swarm-stop" -> {
            if (Topology.SWARM_ID.equals(sig.swarmId())) {
              log.info("Stop signal for swarm {}", sig.swarmId());
              lifecycle.stop(sig.correlationId(), sig.idempotencyKey());
              Confirmation conf = Confirmation.success("swarm-stop", sig.swarmId(), null, null,
                  sig.correlationId(), sig.idempotencyKey(), "Stopped", null);
              outcomes.put(key, conf);
              sendConfirmation(conf);
            }
          }
          case "swarm-remove" -> {
            if (Topology.SWARM_ID.equals(sig.swarmId())) {
              log.info("Remove signal for swarm {}", sig.swarmId());
              lifecycle.remove(sig.correlationId(), sig.idempotencyKey());
              Confirmation conf = Confirmation.success("swarm-remove", sig.swarmId(), null, null,
                  sig.correlationId(), sig.idempotencyKey(), "Removed", null);
              outcomes.put(key, conf);
              sendConfirmation(conf);
            }
          }
          case "config-update" -> {
            if (ROLE.equals(sig.role()) && instanceId.equals(sig.instance())) {
              log.info("Config update for controller {}", instanceId);
              Confirmation conf = Confirmation.success("config-update", null, ROLE, instanceId,
                  sig.correlationId(), sig.idempotencyKey(), null, null);
              outcomes.put(key, conf);
              sendConfirmation(conf);
            }
          }
        }
      } catch (Exception e) {
        log.warn("signal parse", e);
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
    } else if (routingKey.startsWith("sig.status-request")) {
      log.info("Status request received: {}", routingKey);
      sendStatusFull();
    } else if (routingKey.startsWith("ev.ready.")) {
      String rest = routingKey.substring("ev.ready.".length());
      String[] parts = rest.split(Pattern.quote("."), 2);
      if (parts.length == 2) {
        try {
          JsonNode node = mapper.readTree(body);
          boolean enabled = node.path("data").path("enabled").asBoolean(true);
          if (!enabled) {
            boolean ready = lifecycle.markReady(parts[0], parts[1]);
            if (ready && pendingTemplateKey != null) {
              Confirmation conf = Confirmation.success("swarm-template", Topology.SWARM_ID, null, null,
                  pendingTemplateCorrelation, pendingTemplateIdempotency, "Ready", null);
              outcomes.put(pendingTemplateKey, conf);
              sendConfirmation(conf);
              pendingTemplateKey = null;
            }
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
            "sig.swarm-stop.*")
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
            "sig.swarm-stop.*")
        .controlOut(rk)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private void sendConfirmation(Confirmation conf) {
    try {
      String scope = conf.swarmId() != null ? conf.swarmId() : conf.role() + "." + conf.instance();
      String rk = "ev." + conf.result() + "." + conf.signal() + "." + scope;
      rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, mapper.writeValueAsString(conf));
    } catch (Exception e) {
      log.warn("confirmation send", e);
    }
  }
}
