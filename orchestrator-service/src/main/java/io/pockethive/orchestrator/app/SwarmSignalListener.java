package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Listens for swarm controller readiness events and dispatches swarm start signals.
 */
@Component
public class SwarmSignalListener {
    private final AmqpTemplate rabbit;
    private final SwarmPlanRegistry plans;

    public SwarmSignalListener(AmqpTemplate rabbit, SwarmPlanRegistry plans) {
        this.rabbit = rabbit;
        this.plans = plans;
    }

    @RabbitListener(queues = "#{controlQueue.name}")
    public void handle(@Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey != null && routingKey.startsWith("ev.ready.swarm-controller.")) {
            String instance = routingKey.substring("ev.ready.swarm-controller.".length());
            plans.remove(instance).ifPresent(plan ->
                    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                            "sig.swarm-start." + plan.id(), plan));
        }
    }
}

