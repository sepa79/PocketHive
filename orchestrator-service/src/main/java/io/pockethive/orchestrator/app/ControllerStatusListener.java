package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmLifecycleStatus;
import io.pockethive.orchestrator.domain.SwarmStateStore;
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
import java.util.Objects;

/**
 * Consumes swarm-controller aggregate status events and updates the local store.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);
    private static final Duration DEGRADED_AFTER = Duration.ofSeconds(20);
    private static final Duration FAILED_AFTER = Duration.ofSeconds(40);
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";

    private final SwarmStore store;
    private final ObjectMapper mapper;
    private final ControlPlaneStatusRequestPublisher statusRequests;
    private final SwarmSignalListener swarmSignals;
    private final SwarmStateStore stateStore;

    public ControllerStatusListener(SwarmStore store,
                                    ObjectMapper mapper,
                                    ControlPlaneStatusRequestPublisher statusRequests,
                                    SwarmSignalListener swarmSignals,
                                    SwarmStateStore stateStore) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.statusRequests = Objects.requireNonNull(statusRequests, "statusRequests");
        this.swarmSignals = Objects.requireNonNull(swarmSignals, "swarmSignals");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    @RabbitListener(queues = "#{controllerStatusQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            log.warn("Received controller status message with null or blank routing key; payload snippet={}", snippet(body));
            throw new IllegalArgumentException("Controller status routing key must not be null or blank");
        }
        if (body == null || body.isBlank()) {
            log.warn("Received controller status message with null or blank payload for routing key {}", routingKey);
            throw new IllegalArgumentException("Controller status payload must not be null or blank");
        }
        String payloadSnippet = snippet(body);
        if (routingKey.startsWith("event.metric.status-")) {
            log.debug("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
        } else {
            log.info("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
        }
        try {
            boolean statusFull = routingKey.startsWith("event.metric.status-full.");
            boolean statusDelta = routingKey.startsWith("event.metric.status-delta.");
            JsonNode node = mapper.readTree(body);
            JsonNode scope = node.path("scope");
            String swarmId = scope.path("swarmId").asText(null);
            String role = scope.path("role").asText(null);
            String controllerInstance = scope.path("instance").asText(null);
            warnMissingScopeFields(routingKey, body, swarmId, role, controllerInstance);
            JsonNode data = node.path("data");
            JsonNode context = data.path("context");
            String swarmStatusText = context.path("swarmStatus").asText(null);
            String runId = context.path("journal").path("runId").asText(null);

            boolean discovered = store.ensureDiscoveredSwarm(swarmId, controllerInstance, runId);
            Swarm swarm = swarmId == null ? null : store.find(swarmId).orElse(null);
            boolean controllerScope = SWARM_CONTROLLER_ROLE.equals(role);

            if (controllerScope && swarm != null) {
                if (statusFull) {
                    store.cacheControllerStatusFull(swarmId, node, Instant.now());
                    swarmSignals.handleControllerStatusFull(routingKey);
                    if (discovered) {
                        requestStatusFull(swarmId, node);
                    }
                } else if (statusDelta) {
                    SwarmStore.DeltaApplyResult result = store.applyControllerStatusDelta(swarmId, node, Instant.now());
                    if (result == SwarmStore.DeltaApplyResult.MISSING_BASELINE) {
                        requestStatusFull(swarmId, node);
                    } else if (result == SwarmStore.DeltaApplyResult.REJECTED_FULL_ONLY_FIELDS) {
                        log.warn("Ignoring status-delta with full-only fields for swarm {} rk={}", swarmId, routingKey);
                        return;
                    }
                }
            } else if (statusDelta && controllerScope && swarmId != null) {
                requestStatusFull(swarmId, node);
                return;
            }

            if (swarmId != null) {
                // Workloads enablement is reported as data.enabled on status metrics.
                JsonNode enabledNode = data.get("enabled");
                Boolean workloadsEnabled = enabledNode != null && enabledNode.isBoolean()
                    ? enabledNode.asBoolean()
                    : null;

                // Derive lifecycle status from controller view so plan‑driven start/stop
                // keeps the Orchestrator store in sync even when no explicit
                // /start or /stop REST call was issued.
                if (swarmStatusText != null && !swarmStatusText.isBlank()) {
                    String normalized = swarmStatusText.trim().toUpperCase();

                    switch (normalized) {
                        case "RUNNING" -> {
                            if (Boolean.TRUE.equals(workloadsEnabled)) {
                                // Use the existing lifecycle helper so that
                                // status transitions obey the state machine.
                                store.markStartConfirmed(swarmId);
                            }
                        }
                        case "STOPPED" -> {
                            if (Boolean.FALSE.equals(workloadsEnabled)) {
                                if (discovered) {
                                    // When rebuilding from controller status events after an orchestrator restart,
                                    // the newly registered swarm may still be in READY. Walk through the normal
                                    // state machine to reach STOPPED without tripping illegal transitions.
                                    store.markStartConfirmed(swarmId);
                                }
                                // Plan‑driven stop: make sure we walk through
                                // STOPPING -> STOPPED so the local state
                                // machine is satisfied.
                                store.updateStatus(swarmId, SwarmLifecycleStatus.STOPPING);
                                store.updateStatus(swarmId, SwarmLifecycleStatus.STOPPED);
                            }
                        }
                        case "FAILED" -> store.updateStatus(swarmId, SwarmLifecycleStatus.FAILED);
                        default -> { /* leave store status unchanged for other states */ }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("status parse", e);
        }
    }

    private void requestStatusFull(String swarmId, JsonNode sourceEnvelope) {
        if (swarmId == null || swarmId.isBlank()) {
            return;
        }
        String corr = java.util.UUID.randomUUID().toString();
        String idem = "status-request:" + java.util.UUID.randomUUID();
        java.util.Map<String, Object> runtime = stateStore.requireRuntimeFromStatusEnvelope(sourceEnvelope, "status");
        statusRequests.requestStatusForSwarm(swarmId, corr, idem, runtime);
    }

    @Scheduled(fixedRate = 5000L)
    public void expire() {
        store.pruneStaleControllers(FAILED_AFTER);
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

    private void warnMissingScopeFields(String routingKey,
                                        String body,
                                        String swarmId,
                                        String role,
                                        String instance) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (swarmId == null || swarmId.isBlank()) {
            missing.add("swarmId");
        }
        if (role == null || role.isBlank()) {
            missing.add("role");
        }
        if (instance == null || instance.isBlank()) {
            missing.add("instance");
        }
        if (!missing.isEmpty()) {
            log.warn("Received controller status payload with missing scope fields {}; rk={} payload snippet={}",
                missing, routingKey, snippet(body));
        }
    }
}
