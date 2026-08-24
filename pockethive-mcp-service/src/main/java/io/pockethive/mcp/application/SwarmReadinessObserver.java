package io.pockethive.mcp.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Performs one authoritative readiness observation; retry timing belongs to the client. */
@Service
public final class SwarmReadinessObserver {
    private final OwnerApiPort owner;
    private final ObjectMapper mapper;

    public SwarmReadinessObserver(OwnerApiPort owner, ObjectMapper mapper) {
        this.owner = owner;
        this.mapper = mapper;
    }

    public SwarmReadinessResult observe(String path, String swarmId) {
        Status status = status(owner.get(path));
        return new SwarmReadinessResult(status.ready(), swarmId, status.totals(), status.controllerState().name(), 1);
    }

    private Status status(Object response) {
        JsonNode projection = mapper.valueToTree(response);
        JsonNode controllerState = projection.path("controllerState");
        JsonNode workloadState = projection.path("workloadState");
        JsonNode observationStale = projection.path("observationStale");
        JsonNode observation = projection.path("observation");
        JsonNode startupReady = observation.path("startupReady");
        JsonNode expectedWorkers = observation.path("expectedWorkers");
        JsonNode workers = observation.path("workers");
        if (!projection.isObject()
            || !nonBlankText(controllerState)
            || !nonBlankText(workloadState)
            || !observationStale.isBoolean()
            || !observation.isObject()
            || !startupReady.isBoolean()
            || !expectedWorkers.isArray()
            || !workers.isArray()) {
            throw invalidStatus();
        }
        int healthy = 0;
        for (JsonNode worker : workers) {
            JsonNode stale = worker.path("stale");
            if (!worker.isObject() || !stale.isBoolean()) {
                throw invalidStatus();
            }
            if (!stale.booleanValue()) {
                healthy++;
            }
        }
        int desired = expectedWorkers.size();
        Map<String, Object> totals = Map.of("desired", desired, "healthy", healthy);
        return new Status(
            requiredState(ControllerState.class, controllerState.textValue()),
            requiredState(WorkloadState.class, workloadState.textValue()),
            observationStale.booleanValue(),
            startupReady.booleanValue(),
            desired,
            healthy,
            totals);
    }

    private static boolean nonBlankText(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank();
    }

    private static ToolExecutionException invalidStatus() {
        return new ToolExecutionException("SWARM_STATUS_INVALID",
            "Owner response must contain the canonical Orchestrator state and startup observation");
    }

    private static <T extends Enum<T>> T requiredState(Class<T> stateType, String value) {
        try {
            return Enum.valueOf(stateType, value);
        } catch (IllegalArgumentException exception) {
            throw invalidStatus();
        }
    }

    private record Status(ControllerState controllerState, WorkloadState workloadState, boolean observationStale, boolean startupReady, int desired, int healthy, Map<String, Object> totals) {
        boolean ready() {
            return desired > 0
                && healthy >= desired
                && controllerState == ControllerState.READY
                && workloadState == WorkloadState.STOPPED
                && !observationStale
                && startupReady;
        }
    }
}
