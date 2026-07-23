package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;

/**
 * Consumes swarm-controller aggregate status events and updates the local store.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);
    private static final Duration FAILED_AFTER = Duration.ofSeconds(40);
    private static final String SWARM_CONTROLLER_ROLE = "swarm-controller";

    private final SwarmStore store;
    private final ObjectMapper mapper;
    private final ControlPlaneCodec codec;
    private final ControlPlaneStatusRequestPublisher statusRequests;
    private final SwarmSignalListener swarmSignals;
    private final HiveJournal hiveJournal;
    private final ControlPlaneJournalErrors journalErrors;

    public ControllerStatusListener(SwarmStore store,
                                    ObjectMapper mapper,
                                    ControlPlaneCodec codec,
                                    ControlPlaneStatusRequestPublisher statusRequests,
                                    SwarmSignalListener swarmSignals,
                                    HiveJournal hiveJournal) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.codec = Objects.requireNonNull(codec, "codec");
        this.statusRequests = Objects.requireNonNull(statusRequests, "statusRequests");
        this.swarmSignals = Objects.requireNonNull(swarmSignals, "swarmSignals");
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.journalErrors = new ControlPlaneJournalErrors(this.hiveJournal, "orchestrator", "controller-status-listener");
    }

    @RabbitListener(queues = "#{controllerStatusQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        // Controller status messages are control-plane traffic: never requeue on failures (avoid storms).
        try {
            if (routingKey == null || routingKey.isBlank()) {
                log.error("Received controller status message with null or blank routing key; payload snippet={}", snippet(body));
                journalParseError("hive", routingKey, "missing routing key", body, null);
                return;
            }
            if (body == null || body.isBlank()) {
                log.error("Received controller status message with null or blank payload for routing key {}", routingKey);
                journalParseError("hive", routingKey, "missing payload", body, null);
                return;
            }
            String payloadSnippet = snippet(body);
            RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
            boolean statusFull = eventKey != null
                && ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(eventKey.type());
            boolean statusDelta = eventKey != null
                && ControlPlaneEventTypes.METRIC_STATUS_DELTA.equals(eventKey.type());
            if (statusFull || statusDelta) {
                log.debug("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
            } else {
                log.info("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
            }
            StatusMetric status = codec.decode(body, routingKey, StatusMetric.class);
            JsonNode node = mapper.valueToTree(status);
            String swarmId = status.scope().swarmId();
            String role = status.scope().role();
            String controllerInstance = status.scope().instance();
            JsonNode data = node.path("data");
            JsonNode context = data.path("context");
            Swarm swarm = swarmId == null ? null : store.find(swarmId).orElse(null);
            boolean controllerScope = SWARM_CONTROLLER_ROLE.equals(role);

            if (controllerScope && swarm == null) {
                log.warn("Ignoring controller status for unregistered swarm {} instance={}", swarmId, controllerInstance);
                return;
            }
            if (controllerScope) {
                if (statusFull) {
                    hydrateNetworkMetadata(swarm, context);
                    Instant observedAt = Instant.now();
                    store.cacheControllerStatusFull(swarmId, node, observedAt);
                    updateObservation(swarm, context, observedAt);
                    swarmSignals.handleControllerStatusFull(routingKey, node);
                } else if (statusDelta) {
                    Instant observedAt = Instant.now();
                    SwarmStore.DeltaApplyResult result = store.applyControllerStatusDelta(swarmId, node, observedAt);
                    if (result == SwarmStore.DeltaApplyResult.MISSING_BASELINE) {
                        requestStatusFull(swarmId);
                    } else if (result == SwarmStore.DeltaApplyResult.REJECTED_FULL_ONLY_FIELDS) {
                        log.warn("Ignoring status-delta with full-only fields for swarm {} rk={}", swarmId, routingKey);
                        return;
                    } else if (result == SwarmStore.DeltaApplyResult.MERGED) {
                        updateObservation(swarm, swarm.getControllerStatusFull().path("data").path("context"), observedAt);
                        swarmSignals.handleControllerObservation(swarmId);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Ignoring controller status message due to handler exception; rk={} payload snippet={}", routingKey, snippet(body), e);
            journalParseError(bestEffortSwarmIdFromRouting(routingKey), routingKey, "handler exception", body, e);
        }
    }

    private void updateObservation(Swarm swarm, JsonNode context, Instant observedAt) {
        ControllerState controllerState = requiredEnum(
            ControllerState.class, context.path("controllerState").asText(null), "controllerState");
        WorkloadState workloadState = requiredEnum(
            WorkloadState.class, context.path("workloadState").asText(null), "workloadState");
        Health health = requiredEnum(Health.class, context.path("health").asText(null), "health");
        Map<String, Object> observation = mapper.convertValue(
            context,
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        swarm.updateObservation(
            controllerState,
            workloadState,
            health,
            RuntimeResourceState.PRESENT,
            observation,
            observedAt);
    }

    private static <E extends Enum<E>> E requiredEnum(
        Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown " + field + ": " + value, exception);
        }
    }

    private void requestStatusFull(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return;
        }
        String corr = java.util.UUID.randomUUID().toString();
        String idem = "status-request:" + java.util.UUID.randomUUID();
        statusRequests.requestStatusForSwarm(swarmId, corr, idem);
    }

    private void hydrateNetworkMetadata(Swarm swarm, JsonNode context) {
        if (swarm == null || context == null || !context.isObject()) {
            return;
        }
        String sutId = textOrNull(context.path("sutId"));
        if (sutId != null) {
            swarm.setSutId(sutId);
        }
        NetworkMode networkMode = parseNetworkMode(textOrNull(context.path("networkMode")));
        swarm.setNetworkMode(networkMode);
        String networkProfileId = textOrNull(context.path("networkProfileId"));
        swarm.setNetworkProfileId(networkMode == NetworkMode.PROXIED ? networkProfileId : null);
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

    private void journalParseError(String swarmId,
                                  String routingKey,
                                  String reason,
                                  String body,
                                  Exception exception) {
        String resolvedSwarmId = swarmId != null && !swarmId.isBlank() ? swarmId : "hive";
        journalErrors.errorDrop(
            resolvedSwarmId,
            HiveJournal.Direction.IN,
            "status-parse-error",
            new ControlScope(resolvedSwarmId, "orchestrator", "controller-status-listener"),
            routingKey,
            reason,
            body,
            exception);
    }

    private static String bestEffortSwarmIdFromRouting(String routingKey) {
        var parsed = io.pockethive.controlplane.routing.ControlPlaneRouting.parseEvent(routingKey);
        return parsed != null && parsed.swarmId() != null && !parsed.swarmId().isBlank()
            ? parsed.swarmId()
            : "hive";
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static NetworkMode parseNetworkMode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("networkMode must be present in controller status");
        }
        return NetworkMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
