package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Handles control-plane signals for orchestrator and dispatches swarm plans when
 * controllers become ready.
 */
@Component
@EnableScheduling
public class SwarmSignalListener {
    private static final String ROLE = "orchestrator";
    private static final String SCOPE = "hive";
    private static final long STATUS_INTERVAL_MS = 5000L;
    private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);

    private final AmqpTemplate rabbit;
    private final SwarmPlanRegistry plans;
    private final SwarmRegistry registry;
    private final ObjectMapper json;
    private final String instanceId;

    public SwarmSignalListener(AmqpTemplate rabbit,
                               SwarmPlanRegistry plans,
                               SwarmRegistry registry,
                               ObjectMapper json,
                               @Qualifier("instanceId") String instanceId) {
        this.rabbit = rabbit;
        this.plans = plans;
        this.registry = registry;
        this.json = json;
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
        log.info("received {} : {}", routingKey, body);
        if (routingKey.startsWith("ev.ready.swarm-controller.")) {
            String inst = routingKey.substring("ev.ready.swarm-controller.".length());
            plans.remove(inst).ifPresent(plan -> {
                try {
                    String payload = json.writeValueAsString(plan);
                    log.info("sending swarm-template for {} via controller {}", plan.id(), inst);
                    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                        "sig.swarm-template." + plan.id(), payload);
                } catch (Exception e) {
                    log.warn("template send", e);
                }
            });
        }
    }

    @Scheduled(fixedRate = STATUS_INTERVAL_MS)
    public void status() {
        sendStatusDelta();
    }

    private void sendStatusFull() {
        String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
        String rk = "ev.status-full." + ROLE + "." + instanceId;
        String json = new StatusEnvelopeBuilder()
            .kind("status-full")
            .role(ROLE)
            .instance(instanceId)
            .swarmId(SCOPE)
            .enabled(true)
            .controlIn(controlQueue)
            .controlRoutes(
                "ev.ready.*",
                "ev.error.*",
                "ev.status-full.swarm-controller.*",
                "ev.status-delta.swarm-controller.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    }

    private void sendStatusDelta() {
        String controlQueue = Topology.CONTROL_QUEUE + "." + ROLE + "." + instanceId;
        String rk = "ev.status-delta." + ROLE + "." + instanceId;
        String json = new StatusEnvelopeBuilder()
            .kind("status-delta")
            .role(ROLE)
            .instance(instanceId)
            .swarmId(SCOPE)
            .enabled(true)
            .controlIn(controlQueue)
            .controlRoutes(
                "ev.ready.*",
                "ev.error.*",
                "ev.status-full.swarm-controller.*",
                "ev.status-delta.swarm-controller.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    }
}

