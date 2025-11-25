package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates diagnostics reported by workers into a swarm-level view.
 * <p>
 * Workers are expected to emit a {@code data.diagnostics} object in their
 * status events; this aggregator collects the latest diagnostics per role
 * so the Swarm Controller can surface them in its own status payload.
 */
final class SwarmDiagnosticsAggregator {

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper mapper;
  private final Map<String, Map<String, Object>> byRole = new ConcurrentHashMap<>();

  SwarmDiagnosticsAggregator(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  void updateFromWorkerStatus(String role, String instance, JsonNode dataNode) {
    if (role == null || role.isBlank() || dataNode == null || dataNode.isMissingNode() || dataNode.isNull()) {
      return;
    }
    JsonNode diagnostics = dataNode.path("diagnostics");
    if (!diagnostics.isObject() || diagnostics.isEmpty()) {
      return;
    }
    Map<String, Object> asMap = mapper.convertValue(diagnostics, MAP_TYPE);
    if (asMap == null || asMap.isEmpty()) {
      return;
    }
    byRole.put(role, Map.copyOf(asMap));
  }

  Map<String, Map<String, Object>> snapshot() {
    if (byRole.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<String, Object>> copy = new LinkedHashMap<>(byRole.size());
    byRole.forEach((role, diag) -> copy.put(role, diag));
    return Collections.unmodifiableMap(copy);
  }
}

