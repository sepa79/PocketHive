package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Consumes swarm-controller aggregate status events and updates the local registry.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);
    private static final Duration DEGRADED_AFTER = Duration.ofSeconds(20);
    private static final Duration FAILED_AFTER = Duration.ofSeconds(40);

    private final SwarmRegistry registry;
    private final ObjectMapper mapper;
    private final ControlPlaneStatusRequestPublisher statusRequests;
    private final SwarmSignalListener swarmSignals;

    public ControllerStatusListener(SwarmRegistry registry,
                                    ObjectMapper mapper,
                                    ControlPlaneStatusRequestPublisher statusRequests,
                                    SwarmSignalListener swarmSignals) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.statusRequests = Objects.requireNonNull(statusRequests, "statusRequests");
        this.swarmSignals = Objects.requireNonNull(swarmSignals, "swarmSignals");
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
            JsonNode node = mapper.readTree(body);
            if (routingKey.startsWith("event.metric.status-full.")) {
                swarmSignals.handleControllerStatusFull(routingKey);
            }
            JsonNode scope = node.path("scope");
            String swarmId = scope.path("swarmId").asText(null);
            String role = scope.path("role").asText(null);
            String controllerInstance = scope.path("instance").asText(null);
            warnMissingScopeFields(routingKey, body, swarmId, role, controllerInstance);
            JsonNode data = node.path("data");
            JsonNode context = data.path("context");
            String swarmStatusText = context.path("swarmStatus").asText(null);
            String runId = context.path("journal").path("runId").asText(null);

            boolean discovered = ensureSwarmRegistered(swarmId, controllerInstance, runId);

            if (swarmId != null && swarmStatusText != null) {
                registry.refresh(swarmId, map(swarmStatusText));
            }

            if (discovered && swarmId != null) {
                String corr = java.util.UUID.randomUUID().toString();
                String idem = "status-request:" + java.util.UUID.randomUUID();
                statusRequests.requestStatusForSwarm(swarmId, corr, idem);
            }

            if (swarmId != null) {
                // Workloads enablement is reported as data.enabled on status metrics.
                boolean workloadsKnown = true;
                boolean workloadsEnabled = data.path("enabled").asBoolean(false);
                registry.updateWorkEnabled(swarmId, workloadsEnabled);

                // Derive SwarmStatus from controller view so plan‑driven start/stop
                // keeps the Orchestrator registry in sync even when no explicit
                // /start or /stop REST call was issued.
                if (swarmStatusText != null && !swarmStatusText.isBlank()) {
                    String normalized = swarmStatusText.trim().toUpperCase();

                    switch (normalized) {
                        case "RUNNING" -> {
                            if (workloadsKnown && workloadsEnabled) {
                                // Use the existing lifecycle helper so that
                                // status transitions obey the state machine.
                                registry.markStartConfirmed(swarmId);
                            }
                        }
                        case "STOPPED" -> {
                            if (workloadsKnown && !workloadsEnabled) {
                                if (discovered) {
                                    // When rebuilding from controller status events after an orchestrator restart,
                                    // the newly registered swarm may still be in READY. Walk through the normal
                                    // state machine to reach STOPPED without tripping illegal transitions.
                                    registry.markStartConfirmed(swarmId);
                                }
                                // Plan‑driven stop: make sure we walk through
                                // STOPPING -> STOPPED so the local state
                                // machine is satisfied.
                                registry.updateStatus(swarmId, SwarmStatus.STOPPING);
                                registry.updateStatus(swarmId, SwarmStatus.STOPPED);
                            }
                        }
                        case "FAILED" -> registry.updateStatus(swarmId, SwarmStatus.FAILED);
                        default -> { /* leave registry status unchanged for other states */ }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("status parse", e);
        }
    }

    private boolean ensureSwarmRegistered(String swarmId, String controllerInstance, String runId) {
        if (swarmId == null || swarmId.isBlank()) {
            return false;
        }
        if (registry.find(swarmId).isPresent()) {
            return false;
        }
        if (controllerInstance == null || controllerInstance.isBlank()) {
            return false;
        }
        Swarm swarm = new Swarm(swarmId, controllerInstance, controllerInstance, runId);
        registry.register(swarm);
        registry.updateStatus(swarmId, SwarmStatus.CREATING);
        registry.updateStatus(swarmId, SwarmStatus.READY);
        return true;
    }

    private SwarmHealth map(String s) {
        if (s == null) return SwarmHealth.UNKNOWN;
        if ("RUNNING".equalsIgnoreCase(s)) return SwarmHealth.RUNNING;
        if ("FAILED".equalsIgnoreCase(s)) return SwarmHealth.FAILED;
        if ("DEGRADED".equalsIgnoreCase(s)) return SwarmHealth.DEGRADED;
        return SwarmHealth.DEGRADED;
    }

    @Scheduled(fixedRate = 5000L)
    public void expire() {
        registry.expire(DEGRADED_AFTER, FAILED_AFTER);
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
