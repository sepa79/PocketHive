package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.payload.RoleContext;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Handles control-plane signals for orchestrator and dispatches swarm plans when
 * controllers become ready.
 */
@Component
@EnableScheduling
public class SwarmSignalListener {
    private static final String ROLE = "orchestrator";
    private static final long STATUS_INTERVAL_MS = 5000L;
    private static final Duration TEMPLATE_TIMEOUT = Duration.ofMillis(120_000L);
    private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);

    private static final String BROADCAST_INSTANCE = "ALL";

    private final SwarmPlanRegistry plans;
    private final SwarmRegistry registry;
    private final SwarmCreateTracker creates;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper json;
    private final ManagerControlPlane controlPlane;
    private final ControlPlaneEmitter controlEmitter;
    private final ControlPlaneTopologyDescriptor topology;
    private final ControlPlaneIdentity identity;
    private final String instanceId;
    private final String controlQueue;
    private final List<String> controlRoutes;

    public SwarmSignalListener(SwarmPlanRegistry plans,
                               SwarmCreateTracker creates,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ObjectMapper json,
                               ManagerControlPlane controlPlane,
                               ControlPlaneEmitter controlEmitter,
                               ControlPlaneIdentity managerControlPlaneIdentity,
                               @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
                               @Qualifier("managerControlQueueName") String controlQueue) {
        this.plans = plans;
        this.creates = creates;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.json = json.findAndRegisterModules();
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
        this.topology = Objects.requireNonNull(descriptor, "descriptor");
        this.identity = Objects.requireNonNull(managerControlPlaneIdentity, "identity");
        this.instanceId = identity.instanceId();
        this.controlQueue = Objects.requireNonNull(controlQueue, "controlQueue");
        this.controlRoutes = List.copyOf(resolveControlRoutes(descriptor.routes()));
        try {
            sendStatusFull();
        } catch (Exception e) {
            log.warn("initial status", e);
        }
    }

    @RabbitListener(queues = "#{managerControlQueueName}")
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
            instanceId, CommandTarget.SWARM, args);
    }

    private void emitCreateReady(Pending info) {
        if (info == null) {
            return;
        }
        try {
            ControlPlaneEmitter emitter = emitterForSwarm(info.swarmId());
            ControlPlaneEmitter.ReadyContext context = ControlPlaneEmitter.ReadyContext.builder(
                    "swarm-create",
                    requireText(info.correlationId(), "swarm-create correlationId"),
                    requireText(info.idempotencyKey(), "swarm-create idempotencyKey"),
                    CommandState.status("Ready"))
                .timestamp(Instant.now())
                .build();
            logReady(context);
            emitter.emitReady(context);
        } catch (Exception e) {
            log.warn("create ready send", e);
        }
    }

    private void emitCreateError(Pending info) {
        if (info == null) {
            return;
        }
        try {
            ControlPlaneEmitter emitter = emitterForSwarm(info.swarmId());
            ControlPlaneEmitter.ErrorContext context = ControlPlaneEmitter.ErrorContext.builder(
                    "swarm-create",
                    requireText(info.correlationId(), "swarm-create correlationId"),
                    requireText(info.idempotencyKey(), "swarm-create idempotencyKey"),
                    CommandState.status("Removed"),
                    "controller-bootstrap",
                    "controller-error",
                    "controller failed")
                .timestamp(Instant.now())
                .retryable(Boolean.TRUE)
                .build();
            logError(context);
            emitter.emitError(context);
        } catch (Exception e) {
            log.warn("create error send", e);
        }
    }

    private void emitCreateTimeout(Pending info) {
        if (info == null) {
            return;
        }
        try {
            ControlPlaneEmitter emitter = emitterForSwarm(info.swarmId());
            ControlPlaneEmitter.ErrorContext context = ControlPlaneEmitter.ErrorContext.builder(
                    "swarm-create",
                    requireText(info.correlationId(), "swarm-create correlationId"),
                    requireText(info.idempotencyKey(), "swarm-create idempotencyKey"),
                    CommandState.status("Failed"),
                    "controller-bootstrap",
                    "timeout",
                    "controller did not become ready in time")
                .timestamp(Instant.now())
                .retryable(Boolean.TRUE)
                .build();
            logError(context);
            emitter.emitError(context);
        } catch (Exception e) {
            log.warn("create timeout send", e);
        }
    }

    private void emitPhaseTimeout(String signal, Pending info, String phase, String message) {
        if (info == null) {
            return;
        }
        try {
            ControlPlaneEmitter emitter = emitterForSwarm(info.swarmId());
            ControlPlaneEmitter.ErrorContext context = ControlPlaneEmitter.ErrorContext.builder(
                    signal,
                    requireText(info.correlationId(), signal + " correlationId"),
                    requireText(info.idempotencyKey(), signal + " idempotencyKey"),
                    CommandState.status("Failed"),
                    phase,
                    "timeout",
                    message)
                .timestamp(Instant.now())
                .retryable(Boolean.TRUE)
                .build();
            logError(context);
            emitter.emitError(context);
        } catch (Exception e) {
            log.warn("phase timeout send {}", signal, e);
        }
    }

    private ControlPlaneEmitter emitterForSwarm(String swarmId) {
        RoleContext role = new RoleContext(requireText(swarmId, "swarmId"), topology.role(), BROADCAST_INSTANCE);
        return ControlPlaneEmitter.using(topology, role, controlPlane.publisher());
    }

    private void logReady(ControlPlaneEmitter.ReadyContext context) {
        log.info("[CTRL] SEND ev.ready signal={} inst={} corr={} idem={}",
            context.signal(), instanceId, context.correlationId(), context.idempotencyKey());
    }

    private void logError(ControlPlaneEmitter.ErrorContext context) {
        log.info("[CTRL] SEND ev.error signal={} inst={} corr={} idem={} code={}",
            context.signal(), instanceId, context.correlationId(), context.idempotencyKey(), context.code());
    }

    private static String requireText(String value, String context) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(context + " must not be blank");
        }
        return value;
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
        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> builder
            .enabled(true)
            .controlIn(controlQueue)
            .controlRoutes(controlRoutes.toArray(String[]::new))
            .data("swarmCount", registry.count()));
        controlEmitter.emitStatusSnapshot(context);
        log.debug("[CTRL] SEND status-full inst={} swarmCount={}", instanceId, registry.count());
    }

    private void sendStatusDelta() {
        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> builder
            .enabled(true)
            .controlIn(controlQueue)
            .controlRoutes(controlRoutes.toArray(String[]::new))
            .data("swarmCount", registry.count()));
        controlEmitter.emitStatusDelta(context);
        log.debug("[CTRL] SEND status-delta inst={} swarmCount={}", instanceId, registry.count());
    }

    private List<String> resolveControlRoutes(ControlPlaneRouteCatalog catalog) {
        if (catalog == null) {
            return List.of();
        }
        List<String> routes = new ArrayList<>();
        collectRoutes(routes, catalog.lifecycleEvents());
        collectRoutes(routes, catalog.statusEvents());
        return routes;
    }

    private void collectRoutes(List<String> target, Set<String> templates) {
        if (templates == null || templates.isEmpty()) {
            return;
        }
        for (String template : templates) {
            if (template == null || template.isBlank()) {
                continue;
            }
            target.add(template.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, instanceId));
        }
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

}
