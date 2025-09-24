package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import com.fasterxml.jackson.core.type.TypeReference;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.CommandState;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.swarm.model.SwarmPlan;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

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
    private static final Duration TEMPLATE_TIMEOUT = Duration.ofMillis(120_000L);
    private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);

    private final AmqpTemplate rabbit;
    private final SwarmPlanRegistry plans;
    private final SwarmRegistry registry;
    private final SwarmCreateTracker creates;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper json;
    private final String instanceId;

    public SwarmSignalListener(AmqpTemplate rabbit,
                               SwarmPlanRegistry plans,
                               SwarmCreateTracker creates,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ObjectMapper json,
                               @Qualifier("instanceId") String instanceId) {
        this.rabbit = rabbit;
        this.plans = plans;
        this.creates = creates;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.json = json.findAndRegisterModules();
        this.instanceId = instanceId;
        try {
            sendStatusFull();
        } catch (Exception e) {
            log.warn("initial status", e);
        }
    }

    @RabbitListener(queues = "#{controlQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null || !routingKey.startsWith("ev.")) return;
        String snippet = snippet(body);
        if (routingKey.startsWith("ev.status-")) {
            log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
        } else {
            log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
        }
        if (routingKey.startsWith("ev.ready.swarm-controller.")) {
            String inst = routingKey.substring("ev.ready.swarm-controller.".length());
            SwarmPlan plan = plans.remove(inst).orElse(null);
            Pending info = creates.remove(inst).orElse(null);
            if (plan != null) {
                try {
                    ControlSignal payload = templateSignal(plan, info);
                    String jsonPayload = json.writeValueAsString(payload);
                    String rk = "sig.swarm-template." + plan.id();
                    log.info("sending swarm-template for {} via controller {}", plan.id(), inst);
                    sendControl(rk, jsonPayload, "sig.swarm-template");
                } catch (Exception e) {
                    log.warn("template send", e);
                }
            } else {
                log.warn("no swarm plan registered for controller {}", inst);
            }
            if (info != null) {
                emitCreateReady(info);
                creates.expectTemplate(info, TEMPLATE_TIMEOUT);
            } else {
                log.warn("no pending create tracked for controller {}", inst);
            }
        } else if (routingKey.startsWith("ev.ready.swarm-stop.")) {
            String swarmId = routingKey.substring("ev.ready.swarm-stop.".length());
            creates.complete(swarmId, Phase.STOP);
            lifecycle.stopSwarm(swarmId);
        } else if (routingKey.startsWith("ev.ready.swarm-remove.")) {
            String swarmId = routingKey.substring("ev.ready.swarm-remove.".length());
            lifecycle.removeSwarm(swarmId);
        } else if (routingKey.startsWith("ev.ready.swarm-template.")) {
            String swarmId = routingKey.substring("ev.ready.swarm-template.".length());
            creates.complete(swarmId, Phase.TEMPLATE);
            registry.markTemplateApplied(swarmId);
        } else if (routingKey.startsWith("ev.ready.swarm-start.")) {
            String swarmId = routingKey.substring("ev.ready.swarm-start.".length());
            creates.complete(swarmId, Phase.START);
            registry.markStartConfirmed(swarmId);
        } else if (routingKey.startsWith("ev.error.swarm-controller.")) {
            String inst = routingKey.substring("ev.error.swarm-controller.".length());
            creates.remove(inst).ifPresent(info -> {
                registry.updateStatus(info.swarmId(), SwarmStatus.FAILED);
                emitCreateError(info);
            });
        } else if (routingKey.startsWith("ev.error.swarm-template.")) {
            String swarmId = routingKey.substring("ev.error.swarm-template.".length());
            creates.complete(swarmId, Phase.TEMPLATE);
            registry.updateStatus(swarmId, SwarmStatus.FAILED);
        } else if (routingKey.startsWith("ev.error.swarm-start.")) {
            String swarmId = routingKey.substring("ev.error.swarm-start.".length());
            creates.complete(swarmId, Phase.START);
            registry.updateStatus(swarmId, SwarmStatus.FAILED);
        } else if (routingKey.startsWith("ev.error.swarm-stop.")) {
            String swarmId = routingKey.substring("ev.error.swarm-stop.".length());
            creates.complete(swarmId, Phase.STOP);
            registry.updateStatus(swarmId, SwarmStatus.FAILED);
        }
    }

    private ControlSignal templateSignal(SwarmPlan plan, Pending info) {
        Map<String, Object> args = json.convertValue(plan, new TypeReference<Map<String, Object>>() {});
        String correlationId = info != null ? info.correlationId() : null;
        String idempotencyKey = info != null ? info.idempotencyKey() : null;
        return new ControlSignal("swarm-template", correlationId, idempotencyKey, plan.id(), null, null,
            CommandTarget.SWARM, null, args);
    }

    private void emitCreateReady(Pending info) {
        try {
            String rk = "ev.ready.swarm-create." + info.swarmId();
            ReadyConfirmation conf = new ReadyConfirmation(
                java.time.Instant.now(),
                info.correlationId(),
                info.idempotencyKey(),
                "swarm-create",
                ConfirmationScope.forSwarm(info.swarmId()),
                CommandState.status("Ready"));
            String payload = json.writeValueAsString(conf);
            sendControl(rk, payload, "ev.ready");
        } catch (Exception e) {
            log.warn("create ready send", e);
        }
    }

    private void emitCreateError(Pending info) {
        try {
            String rk = "ev.error.swarm-create." + info.swarmId();
            ErrorConfirmation conf = new ErrorConfirmation(
                java.time.Instant.now(),
                info.correlationId(),
                info.idempotencyKey(),
                "swarm-create",
                ConfirmationScope.forSwarm(info.swarmId()),
                CommandState.status("Removed"),
                "controller-bootstrap",
                "controller-error",
                "controller failed",
                Boolean.TRUE,
                null);
            String payload = json.writeValueAsString(conf);
            sendControl(rk, payload, "ev.error");
        } catch (Exception e) {
            log.warn("create error send", e);
        }
    }

    private void emitCreateTimeout(Pending info) {
        if (info == null) {
            return;
        }
        try {
            String rk = "ev.error.swarm-create." + info.swarmId();
            ErrorConfirmation conf = new ErrorConfirmation(
                java.time.Instant.now(),
                info.correlationId(),
                info.idempotencyKey(),
                "swarm-create",
                ConfirmationScope.forSwarm(info.swarmId()),
                CommandState.status("Failed"),
                "controller-bootstrap",
                "timeout",
                "controller did not become ready in time",
                Boolean.TRUE,
                null);
            String payload = json.writeValueAsString(conf);
            sendControl(rk, payload, "ev.error");
        } catch (Exception e) {
            log.warn("create timeout send", e);
        }
    }

    private void emitPhaseTimeout(String signal, Pending info, String phase, String message) {
        if (info == null) {
            return;
        }
        try {
            String rk = "ev.error." + signal + "." + info.swarmId();
            ErrorConfirmation conf = new ErrorConfirmation(
                java.time.Instant.now(),
                info.correlationId(),
                info.idempotencyKey(),
                signal,
                ConfirmationScope.forSwarm(info.swarmId()),
                CommandState.status("Failed"),
                phase,
                "timeout",
                message,
                Boolean.TRUE,
                null);
            String payload = json.writeValueAsString(conf);
            sendControl(rk, payload, "ev.error");
        } catch (Exception e) {
            log.warn("phase timeout send {}", signal, e);
        }
    }

    @Scheduled(fixedRate = STATUS_INTERVAL_MS)
    public void status() {
        sendStatusDelta();
    }

    @Scheduled(fixedRate = 2000L)
    public void checkTimeouts() {
        Instant now = Instant.now();
        creates.expire(now).forEach(this::handleTimeout);
    }

    private void handleTimeout(Pending pending) {
        if (pending == null) {
            return;
        }
        Phase phase = pending.phase();
        String swarmId = pending.swarmId();
        if (swarmId == null) {
            return;
        }
        registry.updateStatus(swarmId, SwarmStatus.FAILED);
        switch (phase) {
            case CONTROLLER -> {
                if (pending.instanceId() != null) {
                    plans.remove(pending.instanceId());
                }
                emitCreateTimeout(pending);
            }
            case TEMPLATE -> emitPhaseTimeout("swarm-template", pending, "template", "template confirmation timed out");
            case START -> emitPhaseTimeout("swarm-start", pending, "start", "start confirmation timed out");
            case STOP -> emitPhaseTimeout("swarm-stop", pending, "stop", "stop confirmation timed out");
        }
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
                "ev.ready.#",
                "ev.error.#",
                "ev.status-full.swarm-controller.*",
                "ev.status-delta.swarm-controller.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        sendControl(rk, json, "status");
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
                "ev.ready.#",
                "ev.error.#",
                "ev.status-full.swarm-controller.*",
                "ev.status-delta.swarm-controller.*")
            .controlOut(rk)
            .data("swarmCount", registry.count())
            .toJson();
        sendControl(rk, json, "status");
    }

    private void sendControl(String routingKey, String payload, String context) {
        String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
        String snippet = snippet(payload);
        boolean statusContext = "status".equals(context);
        boolean statusRoutingKey = routingKey != null && routingKey.contains(".status-");
        if (statusContext || statusRoutingKey) {
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

