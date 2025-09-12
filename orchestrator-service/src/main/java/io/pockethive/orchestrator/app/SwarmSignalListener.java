package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmTemplate;
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
    private final ScenarioClient scenarios;
    private final ObjectMapper json;
    private final String instanceId;

    public SwarmSignalListener(AmqpTemplate rabbit,
                               SwarmPlanRegistry plans,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ScenarioClient scenarios,
                               ObjectMapper json,
                               @Qualifier("instanceId") String instanceId) {
        this.rabbit = rabbit;
        this.plans = plans;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.scenarios = scenarios;
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
        if (routingKey.startsWith("sig.swarm-create.")) {
            String swarmId = routingKey.substring("sig.swarm-create.".length());
            try {
                SwarmCreateRequest cmd = json.readValue(body, SwarmCreateRequest.class);
                SwarmTemplate template = scenarios.fetchTemplate(cmd.templateId());
                SwarmPlan plan = new SwarmPlan(swarmId, template.getBees());
                String beeName = BeeNameGenerator.generate("swarm-controller", swarmId);
                log.info("starting swarm-controller {} for swarm {}", beeName, swarmId);
                lifecycle.startSwarm(swarmId, template.getImage(), beeName);
                log.info("publishing ev.swarm-created.{}", swarmId);
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                        "ev.swarm-created." + swarmId, "");
                plans.register(beeName, plan);
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("swarm {} creation failed: {}", swarmId, msg, e);
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                        "ev.swarm-create.error." + swarmId,
                        msg);
            }
        } else if (routingKey.startsWith("sig.swarm-stop.")) {
            String swarmId = routingKey.substring("sig.swarm-stop.".length());
            log.info("stopping swarm {}", swarmId);
            lifecycle.stopSwarm(swarmId);
            registry.find(swarmId).ifPresent(s -> plans.remove(s.getInstanceId()));
            registry.remove(swarmId);
        } else if (routingKey.startsWith("ev.ready.swarm-controller.")) {
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
        } else if (routingKey.startsWith("ev.swarm-ready.")) {
            String swarmId = routingKey.substring("ev.swarm-ready.".length());
            log.info("swarm {} ready", swarmId);
        } else if (routingKey.startsWith("sig.swarm-start.")) {
            String swarmId = routingKey.substring("sig.swarm-start.".length());
            log.info("forwarding start to swarm {}", swarmId);
            registry.find(swarmId).ifPresent(s ->
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                    "sig.swarm-start." + swarmId, body == null ? "" : body));
        } else if (routingKey.startsWith("sig.status-request")) {
            log.info("status requested via {}", routingKey);
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
                "ev.ready.swarm-controller.*",
                "ev.swarm-ready.*")
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
                "ev.ready.swarm-controller.*",
                "ev.swarm-ready.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
    }
}

