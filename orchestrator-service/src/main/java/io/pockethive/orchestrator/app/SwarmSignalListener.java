package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.util.BeeNameGenerator;
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
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper json;
    private final String instanceId;

    public SwarmSignalListener(AmqpTemplate rabbit,
                               SwarmPlanRegistry plans,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ObjectMapper json,
                               @Qualifier("instanceId") String instanceId) {
        this.rabbit = rabbit;
        this.plans = plans;
        this.registry = registry;
        this.lifecycle = lifecycle;
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
        if (routingKey.startsWith("sig.swarm-create.")) {
            String swarmId = routingKey.substring("sig.swarm-create.".length());
            try {
                ScenarioPlan scenario = json.readValue(body, ScenarioPlan.class);
                SwarmPlan plan = scenario.toSwarmPlan(swarmId);
                String beeName = BeeNameGenerator.generate("swarm-controller", swarmId);
                lifecycle.startSwarm(swarmId, scenario.template().getImage(), beeName);
                plans.register(beeName, plan);
            } catch (Exception e) {
                log.warn("swarm {} creation failed", swarmId, e);
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "ev.swarm-create-failed." + swarmId, "");
            }
        } else if (routingKey.startsWith("sig.swarm-stop.")) {
            String swarmId = routingKey.substring("sig.swarm-stop.".length());
            lifecycle.stopSwarm(swarmId);
            registry.find(swarmId).ifPresent(s -> plans.remove(s.getInstanceId()));
            registry.remove(swarmId);
        } else if (routingKey.startsWith("ev.ready.swarm-controller.")) {
            String inst = routingKey.substring("ev.ready.swarm-controller.".length());
            plans.remove(inst).ifPresent(plan ->
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                    "sig.swarm-start." + plan.id(), java.util.Map.of("bees", plan.bees())));
        } else if (routingKey.startsWith("sig.status-request")) {
            sendStatusFull();
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
                "sig.config-update",
                "sig.config-update." + ROLE,
                "sig.config-update." + ROLE + "." + instanceId,
                "sig.status-request",
                "sig.status-request." + ROLE,
                "sig.status-request." + ROLE + "." + instanceId,
                "sig.swarm-create.*",
                "sig.swarm-stop.*",
                "ev.ready.swarm-controller.*")
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
                "sig.config-update",
                "sig.config-update." + ROLE,
                "sig.config-update." + ROLE + "." + instanceId,
                "sig.status-request",
                "sig.status-request." + ROLE,
                "sig.status-request." + ROLE + "." + instanceId,
                "sig.swarm-create.*",
                "sig.swarm-stop.*",
                "ev.ready.swarm-controller.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    }
}

