package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

/**
 * Consumes swarm-controller aggregate status events and updates the local registry.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);
    private static final Duration DEGRADED_AFTER = Duration.ofSeconds(20);
    private static final Duration FAILED_AFTER = Duration.ofSeconds(40);
    private static final Duration WORKER_STATUS_REQUEST_COOLDOWN = Duration.ofSeconds(15);

    private final SwarmRegistry registry;
    private final ObjectMapper mapper;
    private final AmqpTemplate rabbit;
    private final String controlExchange;
    private final String originInstanceId;
    private final ConcurrentMap<String, Long> lastWorkerStatusRequestAtMillis = new ConcurrentHashMap<>();

    public ControllerStatusListener(SwarmRegistry registry, ObjectMapper mapper) {
        this(registry, mapper, null, null, null);
    }

    @Autowired
    public ControllerStatusListener(SwarmRegistry registry,
                                    ObjectMapper mapper,
                                    AmqpTemplate rabbit,
                                    ControlPlaneProperties controlPlaneProperties) {
        this(
            registry,
            mapper,
            rabbit,
            controlPlaneProperties != null ? controlPlaneProperties.getExchange() : null,
            controlPlaneProperties != null ? controlPlaneProperties.getInstanceId() : null
        );
    }

    private ControllerStatusListener(SwarmRegistry registry,
                                     ObjectMapper mapper,
                                     AmqpTemplate rabbit,
                                     String controlExchange,
                                     String originInstanceId) {
        this.registry = registry;
        this.mapper = mapper.findAndRegisterModules();
        this.rabbit = rabbit;
        this.controlExchange = normalise(controlExchange);
        this.originInstanceId = normalise(originInstanceId);
    }

    @RabbitListener(queues = "#{controllerStatusQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null || routingKey.isBlank()) {
            log.warn("Received controller status message with null or blank routing key; payload snippet={}", snippet(body));
            return;
        }
        if (body == null || body.isBlank()) {
            log.warn("Received controller status message with null or blank payload for routing key {}", routingKey);
            return;
        }
        String payloadSnippet = snippet(body);
        if (routingKey.startsWith("event.metric.status-")) {
            log.debug("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
        } else {
            log.info("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
        }
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode scope = node.path("scope");
            String swarmId = scope.path("swarmId").asText(null);
            String role = scope.path("role").asText(null);
            String instanceId = scope.path("instance").asText(null);
            String metricType = node.path("type").asText(null);
            JsonNode data = node.path("data");
            String swarmStatusText = data.path("swarmStatus").asText(null);
            maybeRequestWorkerStatusSnapshots(swarmId, role, metricType);
            boolean recovered = recoverSwarmIfMissing(swarmId, role, instanceId, swarmStatusText, data);
            if (swarmId != null && swarmStatusText != null) {
                registry.refresh(swarmId, map(swarmStatusText));
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
                                if (recovered) {
                                    alignRecoveredStatus(swarmId, SwarmStatus.RUNNING);
                                } else {
                                    // Use the existing lifecycle helper so that
                                    // status transitions obey the state machine.
                                    registry.markStartConfirmed(swarmId);
                                }
                            }
                        }
                        case "STOPPED" -> {
                            if (workloadsKnown && !workloadsEnabled) {
                                if (recovered) {
                                    alignRecoveredStatus(swarmId, SwarmStatus.STOPPED);
                                } else {
                                    // The swarm-controller reports STOPPED continuously (status-delta/full).
                                    // Only apply stop transitions when they are valid for the current local
                                    // state to avoid STOPPED -> STOPPING exceptions and control-plane storms.
                                    registry.find(swarmId).ifPresent(swarm -> {
                                        SwarmStatus current = swarm.getStatus();
                                        if (current == SwarmStatus.STOPPING) {
                                            registry.updateStatus(swarmId, SwarmStatus.STOPPED);
                                        } else if (current == SwarmStatus.RUNNING) {
                                            registry.updateStatus(swarmId, SwarmStatus.STOPPING);
                                            registry.updateStatus(swarmId, SwarmStatus.STOPPED);
                                        }
                                    });
                                }
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

    private boolean recoverSwarmIfMissing(String swarmId,
                                          String role,
                                          String instanceId,
                                          String swarmStatusText,
                                          JsonNode data) {
        if (swarmId == null || swarmId.isBlank()) {
            return false;
        }
        if (!"swarm-controller".equalsIgnoreCase(role)) {
            return false;
        }
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        if (registry.find(swarmId).isPresent()) {
            return false;
        }
        // Recovery path for orchestrator restarts: status events carry enough identity
        // to restore control-plane routing even when in-memory registry was lost.
        String runId = "recovered";
        registry.register(new Swarm(swarmId, instanceId, instanceId, runId));
        if (data != null) {
            registry.updateWorkEnabled(swarmId, data.path("enabled").asBoolean(false));
        }
        alignRecoveredStatus(swarmId, statusFromController(swarmStatusText));
        log.info("Recovered swarm {} from status stream (instance={})", swarmId, instanceId);
        return true;
    }

    private SwarmStatus statusFromController(String swarmStatusText) {
        if (swarmStatusText == null || swarmStatusText.isBlank()) {
            return SwarmStatus.READY;
        }
        return switch (swarmStatusText.trim().toUpperCase()) {
            case "RUNNING" -> SwarmStatus.RUNNING;
            case "STOPPED" -> SwarmStatus.STOPPED;
            case "FAILED" -> SwarmStatus.FAILED;
            default -> SwarmStatus.READY;
        };
    }

    private void alignRecoveredStatus(String swarmId, SwarmStatus target) {
        if (swarmId == null || swarmId.isBlank() || target == null) {
            return;
        }
        tryUpdateStatus(swarmId, SwarmStatus.CREATING);
        tryUpdateStatus(swarmId, SwarmStatus.READY);
        switch (target) {
            case RUNNING -> {
                tryUpdateStatus(swarmId, SwarmStatus.STARTING);
                tryUpdateStatus(swarmId, SwarmStatus.RUNNING);
            }
            case STOPPED -> {
                tryUpdateStatus(swarmId, SwarmStatus.STARTING);
                tryUpdateStatus(swarmId, SwarmStatus.RUNNING);
                tryUpdateStatus(swarmId, SwarmStatus.STOPPING);
                tryUpdateStatus(swarmId, SwarmStatus.STOPPED);
            }
            case FAILED -> tryUpdateStatus(swarmId, SwarmStatus.FAILED);
            default -> {
                // READY is already applied above.
            }
        }
    }

    private void tryUpdateStatus(String swarmId, SwarmStatus next) {
        try {
            registry.updateStatus(swarmId, next);
        } catch (RuntimeException ex) {
            log.debug("Skipped status transition while recovering swarm {} -> {}: {}",
                swarmId, next, ex.getMessage());
        }
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

    private void maybeRequestWorkerStatusSnapshots(String swarmId, String role, String metricType) {
        if (swarmId == null || swarmId.isBlank()) {
            return;
        }
        if (!"swarm-controller".equalsIgnoreCase(role)) {
            return;
        }
        if (!"status-full".equalsIgnoreCase(metricType)) {
            return;
        }
        if (rabbit == null || controlExchange == null || originInstanceId == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = WORKER_STATUS_REQUEST_COOLDOWN.toMillis();
        Long previous = lastWorkerStatusRequestAtMillis.get(swarmId);
        if (previous != null && now - previous < cooldownMs) {
            return;
        }
        lastWorkerStatusRequestAtMillis.put(swarmId, now);

        ControlScope target = ControlScope.forInstance(swarmId, "ALL", "ALL");
        ControlSignal payload = ControlSignals.statusRequest(
            originInstanceId,
            target,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString());
        String routingKey = ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL");
        try {
            rabbit.convertAndSend(controlExchange, routingKey, mapper.writeValueAsString(payload));
            log.debug("Requested full worker status snapshots for swarm {} via {}", swarmId, routingKey);
        } catch (Exception ex) {
            if (previous == null) {
                lastWorkerStatusRequestAtMillis.remove(swarmId);
            } else {
                lastWorkerStatusRequestAtMillis.put(swarmId, previous);
            }
            log.warn("Failed to request worker status snapshots for swarm {} via {}", swarmId, routingKey, ex);
        }
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
