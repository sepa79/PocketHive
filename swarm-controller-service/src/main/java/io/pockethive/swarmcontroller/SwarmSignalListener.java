package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private static final String ROLE = "swarm-controller";
  private final SwarmLifecycle lifecycle;
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private static final long STATUS_INTERVAL_MS = 5000L;

  public SwarmSignalListener(SwarmLifecycle lifecycle,
                             RabbitTemplate rabbit,
                             @Qualifier("instanceId") String instanceId) {
    this.lifecycle = lifecycle;
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    try {
      sendStatusFull();
    } catch (Exception e) {
      log.warn("initial status", e);
    }
  }

  @RabbitListener(queues = "#{controlQueue.name}")
  public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
    if (routingKey == null) return;
    if (routingKey.startsWith("sig.swarm-start.")) {
      String swarmId = routingKey.substring("sig.swarm-start.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        log.info("Start signal for swarm {}", swarmId);
        lifecycle.start();
      }
    } else if (routingKey.startsWith("sig.swarm-stop.")) {
      String swarmId = routingKey.substring("sig.swarm-stop.".length());
      if (Topology.SWARM_ID.equals(swarmId)) {
        log.info("Stop signal for swarm {}", swarmId);
        lifecycle.stop();
      }
    } else if (routingKey.startsWith("sig.status-request")) {
      log.info("Status request received: {}", routingKey);
      sendStatusFull();
    } else if (routingKey.startsWith("sig.config-update")) {
      log.info("Config update received: {} payload={} ", routingKey, body);
    }
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
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-start.*",
            "sig.swarm-stop.*")
        .controlOut(rk)
        .enabled(true)
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
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update." + ROLE,
            "sig.config-update." + ROLE + "." + instanceId,
            "sig.status-request",
            "sig.status-request." + ROLE,
            "sig.status-request." + ROLE + "." + instanceId,
            "sig.swarm-start.*",
            "sig.swarm-stop.*")
        .controlOut(rk)
        .enabled(true)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
}
