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

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.AmqpControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;

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

    private final SwarmPlanRegistry plans;
    private final SwarmRegistry registry;
    private final SwarmCreateTracker creates;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper json;
    private final String instanceId;
    private final ManagerControlPlane controlPlane;

    public SwarmSignalListener(AmqpTemplate rabbit,
                               SwarmPlanRegistry plans,
                               SwarmCreateTracker creates,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ObjectMapper json,
                               @Qualifier("instanceId") String instanceId) {
        this.plans = plans;
        this.creates = creates;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.json = json.findAndRegisterModules();
        this.instanceId = instanceId;
        this.controlPlane = ManagerControlPlane.builder(
            new AmqpControlPlanePublisher(rabbit, Topology.CONTROL_EXCHANGE),
            this.json)
            .identity(new ControlPlaneIdentity(SCOPE, ROLE, instanceId))
            .build();
        try {
            sendStatusFull();
        } catch (Exception e) {
            log.warn("initial status", e);
        }
    }

    @RabbitListener(queues = "#{controlQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            log.warn("Received control-plane event with null or blank routing key; payload snippet={}", snippet(body));
            throw new IllegalArgumentException("Control-plane routing key must not be null or blank");
        }
        if (!routingKey.startsWith("ev.")) {
            log.warn("Received control-plane event with unexpected routing key prefix; rk={} payload snippet={}", routingKey, snippet(body));
            throw new IllegalArgumentException("Control-plane routing key must start with 'ev.'");
        }
        String snippet = snippet(body);
        if (routingKey.startsWith("ev.status-")) {
            log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
            return;
        }
        RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
        if (key == null || key.type() == null) {
            log.warn("Unable to parse control event routing key {}; payload snippet={}", routingKey, snippet);
            throw new IllegalArgumentException("Control-plane routing key is malformed");
        }

        boolean statusEvent = key.type().startsWith("status-");
        if (statusEvent) {
            log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
        } else {
            log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
        }

        if (statusEvent) {
            return;
        }

        if (key.type().startsWith("ready.")) {
            handleReadyEvent(key);
        } else if (key.type().startsWith("error.")) {
            handleErrorEvent(key);
        }
    }

    private void handleReadyEvent(RoutingKey key) {
        switch (key.type()) {
            case "ready.swarm-controller" -> onControllerReady(key);
            case "ready.swarm-template" -> onSwarmTemplateReady(key);
            case "ready.swarm-start" -> onSwarmStartReady(key);
            case "ready.swarm-stop" -> onSwarmStopReady(key);
            case "ready.swarm-remove" -> onSwarmRemoveReady(key);
            default -> log.debug("[CTRL] Ignoring ready event type {}", key.type());
        }
    }

    private void handleErrorEvent(RoutingKey key) {
        switch (key.type()) {
            case "error.swarm-controller" -> onControllerError(key);
            case "error.swarm-template" -> onSwarmTemplateError(key);
            case "error.swarm-start" -> onSwarmStartError(key);
            case "error.swarm-stop" -> onSwarmStopError(key);
            default -> log.debug("[CTRL] Ignoring error event type {}", key.type());
        }
    }

    private void onControllerReady(RoutingKey key) {
        if (!"swarm-controller".equalsIgnoreCase(key.role())) {
            log.debug("Ignoring controller ready for role {}", key.role());
            return;
        }
        String controllerInstance = key.instance();
        if (controllerInstance == null || controllerInstance.isBlank()) {
            log.warn("controller ready event missing instance segment: {}", key);
            return;
        }
        SwarmPlan plan = plans.remove(controllerInstance).orElse(null);
        Pending info = creates.remove(controllerInstance).orElse(null);
        if (plan != null) {
            try {
                ControlSignal payload = templateSignal(plan, info);
                String jsonPayload = json.writeValueAsString(payload);
                String rk = ControlPlaneRouting.signal("swarm-template", plan.id(), "swarm-controller", "ALL");
                log.info("sending swarm-template for {} via controller {}", plan.id(), controllerInstance);
                sendControl(rk, jsonPayload, "sig.swarm-template");
            } catch (Exception e) {
                log.warn("template send", e);
            }
        } else {
            log.warn("no swarm plan registered for controller {}", controllerInstance);
        }
        if (info != null) {
            emitCreateReady(info);
            creates.expectTemplate(info, TEMPLATE_TIMEOUT);
        } else {
            log.warn("no pending create tracked for controller {}", controllerInstance);
        }
    }

    private void onSwarmTemplateReady(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-template ready event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.TEMPLATE);
        registry.markTemplateApplied(swarmId);
    }

    private void onSwarmStartReady(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-start ready event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.START);
        registry.markStartConfirmed(swarmId);
    }

    private void onSwarmStopReady(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-stop ready event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.STOP);
        lifecycle.stopSwarm(swarmId);
    }

    private void onSwarmRemoveReady(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-remove ready event missing swarm id: {}", key);
            return;
        }
        lifecycle.removeSwarm(swarmId);
    }

    private void onControllerError(RoutingKey key) {
        String controllerInstance = key.instance();
        if (controllerInstance == null || controllerInstance.isBlank()) {
            log.warn("controller error event missing instance segment: {}", key);
            return;
        }
        creates.remove(controllerInstance).ifPresent(info -> {
            registry.updateStatus(info.swarmId(), SwarmStatus.FAILED);
            emitCreateError(info);
        });
    }

    private void onSwarmTemplateError(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-template error event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.TEMPLATE);
        registry.updateStatus(swarmId, SwarmStatus.FAILED);
    }

    private void onSwarmStartError(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-start error event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.START);
        registry.updateStatus(swarmId, SwarmStatus.FAILED);
    }

    private void onSwarmStopError(RoutingKey key) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-stop error event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.STOP);
        registry.updateStatus(swarmId, SwarmStatus.FAILED);
    }

    private ControlSignal templateSignal(SwarmPlan plan, Pending info) {
        Map<String, Object> args = json.convertValue(plan, new TypeReference<Map<String, Object>>() {});
        String correlationId = info != null ? info.correlationId() : null;
        String idempotencyKey = info != null ? info.idempotencyKey() : null;
        return new ControlSignal("swarm-template", correlationId, idempotencyKey, plan.id(), null, null,
            CommandTarget.SWARM, args);
    }

    private void emitCreateReady(Pending info) {
        try {
            String rk = ControlPlaneRouting.event("ready.swarm-create", orchestratorScope(info.swarmId()));
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
            String rk = ControlPlaneRouting.event("error.swarm-create", orchestratorScope(info.swarmId()));
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
            String rk = ControlPlaneRouting.event("error.swarm-create", orchestratorScope(info.swarmId()));
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
            String rk = ControlPlaneRouting.event("error." + signal, orchestratorScope(info.swarmId()));
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
        if (routingKey != null && routingKey.startsWith("sig.")) {
            controlPlane.publishSignal(new SignalMessage(routingKey, payload));
        } else {
            controlPlane.publishEvent(new EventMessage(routingKey, payload));
        }
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

    private ConfirmationScope orchestratorScope(String swarmId) {
        return new ConfirmationScope(swarmId, ROLE, "ALL");
    }
}
