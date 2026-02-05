package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only accessor helpers for swarm state derived from cached swarm-controller status metrics.
 *
 * <p>This intentionally avoids introducing a second source of truth: callers should treat
 * {@code status-full} as the cache SSOT, and derive runtime metadata directly from it when emitting
 * control-plane signals that require {@code runtime}.</p>
 */
public final class SwarmStateStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SwarmStore store;
    private final ObjectMapper mapper;

    public SwarmStateStore(SwarmStore store, ObjectMapper mapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Latest cached swarm-controller {@code status-full} envelope for the given swarm.
     */
    public Optional<JsonNode> latestStatusFull(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return Optional.empty();
        }
        return store.find(swarmId.trim())
            .map(Swarm::getControllerStatusFull);
    }

    /**
     * Extract {@code runtime} metadata from a cached {@code status-full} envelope.
     *
     * <p>Signals for non-broadcast scope require runtime; this method fails fast if runtime is absent
     * or missing required fields.</p>
     */
    public Map<String, Object> requireRuntimeFromLatestStatusFull(String swarmId) {
        String resolvedSwarmId = requireText(swarmId, "swarmId");
        JsonNode statusFull = latestStatusFull(resolvedSwarmId)
            .orElseThrow(() -> new IllegalStateException(
                "No cached status-full is available for swarmId=" + resolvedSwarmId));
        return requireRuntimeFromStatusEnvelope(statusFull, "status-full");
    }

    /**
     * Extract {@code runtime} metadata from any status envelope (status-full or status-delta).
     */
    public Map<String, Object> requireRuntimeFromStatusEnvelope(JsonNode envelope, String context) {
        Objects.requireNonNull(envelope, "envelope");
        JsonNode runtimeNode = envelope.get("runtime");
        if (runtimeNode == null || runtimeNode.isNull() || runtimeNode.isMissingNode() || !runtimeNode.isObject()) {
            throw new IllegalStateException("Missing runtime object in " + requireText(context, "context") + " envelope");
        }
        String templateId = requireTextNode(runtimeNode.get("templateId"), "runtime.templateId");
        String runId = requireTextNode(runtimeNode.get("runId"), "runtime.runId");
        Map<String, Object> runtime = mapper.convertValue(runtimeNode, MAP_TYPE);
        // Enforce required keys even if the JSON had odd shapes.
        if (!templateId.equals(runtime.get("templateId")) || !runId.equals(runtime.get("runId"))) {
            runtime = new java.util.LinkedHashMap<>(runtime);
            runtime.put("templateId", templateId);
            runtime.put("runId", runId);
            runtime = Map.copyOf(runtime);
        }
        return runtime;
    }

    private static String requireTextNode(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalStateException("Missing required field " + field);
        }
        String value = node.asText(null);
        return requireText(value, field);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }
}

