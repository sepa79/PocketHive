package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.type.TypeReference;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ControlScope;
import io.pockethive.orchestrator.domain.ScenarioTimelineRegistry;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
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
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.CommandOutcomePolicy;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.observability.ControlPlaneJson;
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

    private final SwarmPlanRegistry plans;
    private final ScenarioTimelineRegistry timelines;
    private final SwarmRegistry registry;
    private final SwarmCreateTracker creates;
    private final ContainerLifecycleManager lifecycle;
    private final ObjectMapper json;
    private final HiveJournal hiveJournal;
    private final ManagerControlPlane controlPlane;
    private final ControlPlaneEmitter controlEmitter;
    private final ControlPlaneTopologyDescriptor topology;
    private final ControlPlaneIdentity identity;
    private final String instanceId;
    private final String controlQueue;
    private final List<String> controlRoutes;
    private final java.time.Instant startedAt;

    public SwarmSignalListener(SwarmPlanRegistry plans,
                               ScenarioTimelineRegistry timelines,
                               SwarmCreateTracker creates,
                               SwarmRegistry registry,
                               ContainerLifecycleManager lifecycle,
                               ObjectMapper json,
                               HiveJournal hiveJournal,
                               ManagerControlPlane controlPlane,
                               ControlPlaneEmitter controlEmitter,
                               ControlPlaneIdentity managerControlPlaneIdentity,
                               @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
                               @Qualifier("managerControlQueueName") String controlQueue) {
        this.plans = plans;
        this.timelines = timelines;
        this.creates = creates;
        this.registry = registry;
        this.lifecycle = lifecycle;
        this.json = json.findAndRegisterModules();
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
        this.topology = Objects.requireNonNull(descriptor, "descriptor");
        this.identity = Objects.requireNonNull(managerControlPlaneIdentity, "identity");
        this.instanceId = identity.instanceId();
        this.controlQueue = Objects.requireNonNull(controlQueue, "controlQueue");
        this.controlRoutes = List.copyOf(resolveControlRoutes(descriptor.routes()));
        this.startedAt = java.time.Instant.now();
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
        if (!routingKey.startsWith("event.")) {
            log.warn("Received control-plane event with unexpected routing key prefix; rk={} payload snippet={}", routingKey, snippet(body));
            throw new IllegalArgumentException("Control-plane routing key must start with 'event.'");
        }
        String snippet = snippet(body);
        if (routingKey.startsWith("event.metric.status-")) {
            log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
            return;
        }
        RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
        if (key == null || key.type() == null) {
            log.warn("Unable to parse control event routing key {}; payload snippet={}", routingKey, snippet);
            throw new IllegalArgumentException("Control-plane routing key is malformed");
        }

        log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);

        if (key.type().startsWith("outcome.")) {
            handleOutcomeEvent(key, routingKey, body);
        }
    }

    void handleControllerStatusFull(String routingKey) {
        RoutingKey key = ControlPlaneRouting.parseEvent(routingKey);
        if (key == null || key.type() == null) {
            log.warn("Unable to parse control status routing key {}", routingKey);
            return;
        }
        if (!"metric.status-full".equalsIgnoreCase(key.type())) {
            return;
        }
        if (!"swarm-controller".equalsIgnoreCase(key.role())) {
            return;
        }
        String controllerInstance = key.instance();
        if (controllerInstance == null || controllerInstance.isBlank()) {
            log.warn("controller status-full event missing instance segment: {}", key);
            return;
        }
        boolean hasPlan = plans.find(controllerInstance).isPresent();
        boolean hasCreate = key.swarmId() != null && !key.swarmId().isBlank()
            && creates.controllerPending(key.swarmId()).isPresent();
        if (!hasPlan && !hasCreate) {
            return;
        }
        onControllerReady(key);
    }

    private void handleOutcomeEvent(RoutingKey key, String routingKey, String body) {
        String command = key.type().substring("outcome.".length());
        String status = null;
        String contextStatus = null;
        String origin = null;
        String correlationId = null;
        String idempotencyKey = null;
        try {
            var root = json.readTree(body);
            status = root.path("data").path("status").asText(null);
            contextStatus = root.path("data").path("context").path("status").asText(null);
            origin = root.path("origin").asText(null);
            correlationId = root.path("correlationId").asText(null);
            idempotencyKey = root.path("idempotencyKey").asText(null);
        } catch (Exception e) {
            log.debug("Failed to parse outcome payload; rk={} payload snippet={}", routingKey, snippet(body), e);
        }

        try {
            String swarmId = key.swarmId();
            if (swarmId != null && !swarmId.isBlank()) {
                Boolean ok = classifyTrackedOutcome(command, status);
                if (ok != null) {
                    var data = new java.util.LinkedHashMap<String, Object>();
                    data.put("status", status);
                    hiveJournal.append(ok
                        ? HiveJournalEntry.info(
                            swarmId,
                            HiveJournal.Direction.IN,
                            "outcome",
                            command,
                            origin != null && !origin.isBlank() ? origin : "unknown",
                            new ControlScope(swarmId, key.role(), key.instance()),
                            correlationId,
                            idempotencyKey,
                            routingKey,
                            data,
                            null,
                            null)
                        : HiveJournalEntry.error(
                            swarmId,
                            HiveJournal.Direction.IN,
                            "outcome",
                            command,
                            origin != null && !origin.isBlank() ? origin : "unknown",
                            new ControlScope(swarmId, key.role(), key.instance()),
                            correlationId,
                            idempotencyKey,
                            routingKey,
                            data,
                            null,
                            null));
                }
            }
        } catch (Exception ignore) {
            // best-effort
        }
        switch (command) {
            case "swarm-template" -> {
                if (isStatus(status, "Ready")) onSwarmTemplateReady(key);
                else onSwarmTemplateError(key);
            }
            case "swarm-start" -> {
                if (CommandOutcomePolicy.isNotReadyStatus(status)) onSwarmStartNotReady(key, contextStatus);
                else if (isStatus(status, "Running")) onSwarmStartReady(key);
                else onSwarmStartError(key);
            }
            case "swarm-stop" -> {
                if (CommandOutcomePolicy.isNotReadyStatus(status)) onSwarmStopNotReady(key, contextStatus);
                else if (isStatus(status, "Stopped")) onSwarmStopReady(key);
                else onSwarmStopError(key);
            }
            case "swarm-remove" -> {
                if (isStatus(status, "Removed")) onSwarmRemoveReady(key);
                else registry.updateStatus(key.swarmId(), SwarmStatus.FAILED);
            }
            default -> log.debug("[CTRL] Ignoring outcome type {}", key.type());
        }
    }

    private static Boolean classifyTrackedOutcome(String command, String status) {
        return switch (command) {
            case "swarm-create" -> isStatus(status, "Ready");
            case "swarm-template" -> isStatus(status, "Ready");
            case "swarm-start" -> isStatus(status, "Running") || CommandOutcomePolicy.isNotReadyStatus(status);
            case "swarm-stop" -> isStatus(status, "Stopped") || CommandOutcomePolicy.isNotReadyStatus(status);
            case "swarm-remove" -> isStatus(status, "Removed");
            default -> null;
        };
    }

    private static boolean isStatus(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return expected.equalsIgnoreCase(actual.trim());
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
                ControlSignal payload = templateSignal(plan, info, controllerInstance);
                String jsonPayload = ControlPlaneJson.write(payload, "swarm-template signal");
                String rk = ControlPlaneRouting.signal("swarm-template", plan.id(), "swarm-controller", controllerInstance);
                log.info("sending swarm-template for {} via controller {}", plan.id(), controllerInstance);
                sendControl(rk, jsonPayload, "signal.swarm-template");
                try {
                    var data = new java.util.LinkedHashMap<String, Object>();
                    data.put("controllerInstance", controllerInstance);
                    hiveJournal.append(HiveJournalEntry.info(
                        plan.id(),
                        HiveJournal.Direction.OUT,
                        "signal",
                        "swarm-template",
                        payload.origin(),
                        payload.scope(),
                        payload.correlationId(),
                        payload.idempotencyKey(),
                        rk,
                        data,
                        null,
                        null));
                } catch (Exception ignore) {
                    // best-effort
                }
            } catch (Exception e) {
                log.warn("template send", e);
            }
        } else {
            log.warn("no swarm plan registered for controller {}", controllerInstance);
        }

        timelines.remove(controllerInstance).ifPresent(planJson -> {
            try {
                Map<String, Object> args = json.readValue(planJson, new TypeReference<Map<String, Object>>() {});
                String swarmId = plan != null ? plan.id() : info != null ? info.swarmId() : null;
                if (swarmId == null || swarmId.isBlank()) {
                    log.warn("cannot send swarm-plan for controller {} without swarm id", controllerInstance);
                    return;
                }
                String signal = io.pockethive.controlplane.ControlPlaneSignals.SWARM_PLAN;
                // Use a fresh correlation/idempotency pair so the manager's
                // duplicate cache does not collapse this together with the
                // swarm-template lifecycle signal.
                String correlationId = java.util.UUID.randomUUID().toString();
                String idempotencyKey = java.util.UUID.randomUUID().toString();
                ControlSignal payload = ControlSignals.swarmPlan(
                    instanceId,
                    ControlScope.forInstance(swarmId, "swarm-controller", controllerInstance),
                    correlationId,
                    idempotencyKey,
                    args);
                String jsonPayload = ControlPlaneJson.write(payload, "swarm-plan signal");
                String rk = ControlPlaneRouting.signal(signal, swarmId, "swarm-controller", controllerInstance);
                log.info("sending swarm-plan for {} via controller {} (corr={}, idem={})",
                    swarmId, controllerInstance, correlationId, idempotencyKey);
                sendControl(rk, jsonPayload, "signal.swarm-plan");
                try {
                    var data = new java.util.LinkedHashMap<String, Object>();
                    data.put("controllerInstance", controllerInstance);
                    hiveJournal.append(HiveJournalEntry.info(
                        swarmId,
                        HiveJournal.Direction.OUT,
                        "signal",
                        signal,
                        payload.origin(),
                        payload.scope(),
                        payload.correlationId(),
                        payload.idempotencyKey(),
                        rk,
                        data,
                        null,
                        null));
                } catch (Exception ignore) {
                    // best-effort
                }
            } catch (Exception e) {
                log.warn("plan send", e);
            }
        });

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

    private void onSwarmStartNotReady(RoutingKey key, String contextStatus) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-start not-ready event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.START);
        updateStatusFromContext(swarmId, contextStatus);
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

    private void onSwarmStopNotReady(RoutingKey key, String contextStatus) {
        String swarmId = key.swarmId();
        if (swarmId == null || swarmId.isBlank()) {
            log.warn("swarm-stop not-ready event missing swarm id: {}", key);
            return;
        }
        creates.complete(swarmId, Phase.STOP);
        updateStatusFromContext(swarmId, contextStatus);
    }

    private void updateStatusFromContext(String swarmId, String contextStatus) {
        SwarmStatus status = parseSwarmStatus(contextStatus);
        if (status == null) {
            return;
        }
        registry.find(swarmId).ifPresent(swarm -> {
            SwarmStatus current = swarm.getStatus();
            if (current == status || current.canTransitionTo(status)) {
                registry.updateStatus(swarmId, status);
            } else {
                log.warn("illegal status transition from outcome context for swarm {}: {} -> {} (ignoring)",
                    swarmId, current, status);
            }
        });
    }

    private SwarmStatus parseSwarmStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SwarmStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("unknown swarm status '{}' in outcome context", status);
            return null;
        }
    }

    private ControlSignal templateSignal(SwarmPlan plan, Pending info, String controllerInstance) {
        Map<String, Object> args = json.convertValue(plan, new TypeReference<Map<String, Object>>() {});
        String correlationId = info != null && info.correlationId() != null && !info.correlationId().isBlank()
            ? info.correlationId()
            : java.util.UUID.randomUUID().toString();
        String idempotencyKey = info != null && info.idempotencyKey() != null && !info.idempotencyKey().isBlank()
            ? info.idempotencyKey()
            : java.util.UUID.randomUUID().toString();
        return ControlSignals.swarmTemplate(
            instanceId,
            ControlScope.forInstance(plan.id(), "swarm-controller", controllerInstance),
            correlationId,
            idempotencyKey,
            args);
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
                    new CommandState(null, null, null))
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
                    new CommandState(null, null, null),
                    "controller-bootstrap",
                    "controller-error",
                    "controller failed")
                .timestamp(Instant.now())
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
                    new CommandState(null, null, null),
                    "controller-bootstrap",
                    "timeout",
                    "controller did not become ready in time")
                .timestamp(Instant.now())
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
                    new CommandState(null, null, null),
                    phase,
                    "timeout",
                    message)
                .timestamp(Instant.now())
                .build();
            logError(context);
            emitter.emitError(context);
        } catch (Exception e) {
            log.warn("phase timeout send {}", signal, e);
        }
    }

    private ControlPlaneEmitter emitterForSwarm(String swarmId) {
        RoleContext role = new RoleContext(requireText(swarmId, "swarmId"), topology.role(), identity.instanceId());
        return ControlPlaneEmitter.using(topology, role, controlPlane.publisher());
    }

    private void logReady(ControlPlaneEmitter.ReadyContext context) {
        log.info("[CTRL] SEND event.outcome type={} inst={} corr={} idem={}",
            context.signal(), instanceId, context.correlationId(), context.idempotencyKey());
    }

    private void logError(ControlPlaneEmitter.ErrorContext context) {
        log.info("[CTRL] SEND event.outcome+alert type={} inst={} corr={} idem={} code={}",
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
        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> {
            var b = builder
                .workPlaneEnabled(false)
                .filesystemEnabled(true)
                .tpsEnabled(false)
                .enabled(true)
                .controlIn(controlQueue)
                .controlRoutes(controlRoutes.toArray(String[]::new))
                .data("swarmCount", registry.count())
                .data("startedAt", startedAt);
            var adapterType = lifecycle.currentComputeAdapterType();
            if (adapterType != null) {
                b.data("computeAdapter", adapterType.name());
            }
        });
        controlEmitter.emitStatusSnapshot(context);
        log.debug("[CTRL] SEND status-full inst={} swarmCount={}", instanceId, registry.count());
    }

    public void requestStatusFull() {
        sendStatusFull();
    }

    private void sendStatusDelta() {
        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> {
            var b = builder
                .workPlaneEnabled(false)
                .tpsEnabled(false)
                .enabled(true)
                .controlIn(controlQueue)
                .controlRoutes(controlRoutes.toArray(String[]::new))
                .data("swarmCount", registry.count());
        });
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
        if (routingKey != null && routingKey.startsWith("signal.")) {
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
