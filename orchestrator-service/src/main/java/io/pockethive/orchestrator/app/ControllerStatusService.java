package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Responsibility: admit controller identity, discover catalogue entries and apply status observations.
 * Must not: decode transport, invent runtime metadata or complete lifecycle operations itself.
 * Contract: RESP-ORCHESTRATOR-INGRESS — docs/architecture/runtime-responsibilities.md.
 */
@Service
public final class ControllerStatusService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ControllerStatusService.class);
    private static final String IDENTITY_CONFLICT = "controller-identity-conflict";
    private final SwarmStore store;
    private final ObjectMapper mapper;
    private final ControlPlaneStatusRequestPublisher statusRequests;
    private final SwarmOperationObservationHandler observations;
    private final SwarmStatusDiscovery discovery;
    private final ControlPlaneJournalErrors journalErrors;

    public ControllerStatusService(SwarmStore store, ObjectMapper mapper,
        ControlPlaneStatusRequestPublisher statusRequests, SwarmOperationObservationHandler observations,
        SwarmStatusDiscovery discovery, HiveJournal journal) {
        this.store = Objects.requireNonNull(store);
        this.mapper = Objects.requireNonNull(mapper);
        this.statusRequests = Objects.requireNonNull(statusRequests);
        this.observations = Objects.requireNonNull(observations);
        this.discovery = Objects.requireNonNull(discovery);
        this.journalErrors = new ControlPlaneJournalErrors(journal, ControlPlaneRoles.ORCHESTRATOR, "controller-status");
    }

    public void accept(StatusMetric status, boolean full, String routingKey) {
        if (!ControlPlaneRoles.SWARM_CONTROLLER.equals(status.scope().role())) {
            return;
        }
        String swarmId = status.scope().swarmId();
        String instance = status.scope().instance();
        JsonNode node = mapper.valueToTree(status);
        String runId = requireText(node.path("runtime").path("runId"), "runId");
        Swarm swarm = store.find(swarmId).orElse(null);
        if (swarm != null && !matches(swarm, instance, runId, routingKey)) {
            return;
        }
        if (!full && (swarm == null || swarm.getControllerStatusFull() == null)) {
            requestStatusFull(swarmId);
            return;
        }
        JsonNode context = node.path("data").path("context");
        if (full) {
            // Validate the complete observation before registering or changing a cached baseline.
            validateObservation(context);
            NetworkMode mode = parseNetworkMode(textOrNull(context.path("networkMode")));
            if (swarm == null) {
                String templateId = requireText(node.path("runtime").path("templateId"), "templateId");
                Swarm candidate = discovery.discover(swarmId, instance, runId, templateId, mode, context);
                swarm = store.registerIfAbsent(candidate);
                if (!matches(swarm, instance, runId, routingKey)) {
                    return;
                }
            }
            Swarm admitted = swarm;
            boolean applied = store.updateIfCurrent(admitted, () -> {
                hydrateNetworkMetadata(admitted, context);
                Instant now = Instant.now();
                store.cacheControllerStatusFull(swarmId, node, now);
                updateObservation(admitted, context, now);
            });
            if (applied) {
                observations.handleControllerStatusFull(swarmId, instance, node);
            } else {
                entryChanged(swarmId, instance, runId, routingKey);
            }
        } else {
            Swarm admitted = swarm;
            var result = new java.util.concurrent.atomic.AtomicReference<SwarmStore.DeltaApplyResult>();
            boolean applied = store.updateIfCurrent(admitted, () -> {
                Instant now = Instant.now();
                var merged = store.applyControllerStatusDelta(swarmId, node, now);
                result.set(merged);
                if (merged == SwarmStore.DeltaApplyResult.MERGED) {
                    updateObservation(admitted, admitted.getControllerStatusFull().path("data").path("context"), now);
                }
            });
            if (!applied) {
                entryChanged(swarmId, instance, runId, routingKey);
                return;
            }
            switch (result.get()) {
                case MISSING_BASELINE, SWARM_NOT_FOUND -> requestStatusFull(swarmId);
                case MERGED -> observations.handleControllerObservation(swarmId);
                case REJECTED_FULL_ONLY_FIELDS, NOT_OBJECT ->
                    throw new IllegalArgumentException("Invalid controller status delta: " + result.get());
            }
        }
    }

    private void entryChanged(String swarmId, String instance, String runId, String routingKey) {
        Swarm current = store.find(swarmId).orElse(null);
        if (current == null || matches(current, instance, runId, routingKey)) {
            requestStatusFull(swarmId);
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 5000L)
    public void expire() {
        store.pruneStaleControllers(java.time.Duration.ofSeconds(40));
    }

    private boolean matches(Swarm swarm, String instance, String runId, String routingKey) {
        if (Objects.equals(swarm.getInstanceId(), instance) && Objects.equals(swarm.getRunId(), runId)) {
            return true;
        }
        String reason = "Controller identity conflict for swarm=" + swarm.getId()
            + "; expected instance=" + swarm.getInstanceId() + " runId=" + swarm.getRunId()
            + "; received instance=" + instance + " runId=" + runId;
        log.error(reason);
        journalErrors.errorDrop(swarm.getId(), HiveJournal.Direction.IN, IDENTITY_CONFLICT,
            new ControlScope(swarm.getId(), ControlPlaneRoles.ORCHESTRATOR, "controller-status"),
            routingKey, reason, "", null);
        return false;
    }

    private static void validateObservation(JsonNode context) {
        requiredEnum(ControllerState.class, context.path("controllerState").asText(null), "controllerState");
        requiredEnum(WorkloadState.class, context.path("workloadState").asText(null), "workloadState");
        requiredEnum(Health.class, context.path("health").asText(null), "health");
    }

    private static String requireText(JsonNode node, String field) {
        String value = textOrNull(node);
        if (value == null) {
            throw new IllegalArgumentException(field + " must be present in controller status");
        }
        return value;
    }

    private void requestStatusFull(String swarmId) {
        statusRequests.requestStatusForSwarm(swarmId, java.util.UUID.randomUUID().toString(),
            "status-request:" + java.util.UUID.randomUUID());
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
