package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SwarmSignalListener {
  private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
  private final SwarmLifecycle lifecycle;

  public SwarmSignalListener(SwarmLifecycle lifecycle) {
    this.lifecycle = lifecycle;
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
    }
  }
}
