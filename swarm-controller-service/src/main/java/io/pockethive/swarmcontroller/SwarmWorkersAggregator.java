package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.controlplane.worker.WorkerRuntimeIdentity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Aggregates per-worker status deltas into a swarm-level worker list snapshot.
 *
 * <p>This is intended for Swarm Controller {@code status-full} publishing so UIs
 * can avoid subscribing directly to worker status fan-out.</p>
 */
final class SwarmWorkersAggregator {

  private static final String WORKER_BEE_ID_FIELD = WorkerRuntimeIdentity.BEE_ID_CONTEXT_FIELD;
  private static final String IDENTITY_DIAGNOSTICS_FIELD = "identityDiagnostics";
  private static final String WORKER_BEE_ID_ECHO_FIELD = "workerBeeIdEcho";
  private static final String WORKER_BEE_ID_ECHO_MISSING = "missing";
  private static final String WORKER_BEE_ID_ECHO_MISMATCH = "mismatch";
  private static final String EXPECTED_BEE_ID_FIELD = "expectedBeeId";
  private static final String ACTUAL_BEE_ID_FIELD = "actualBeeId";

  private final long staleAfterMillis;
  private final BiFunction<String, String, Optional<String>> runtimeBeeIdResolver;
  private final Map<String, WorkerSnapshot> byKey = new ConcurrentHashMap<>();

  SwarmWorkersAggregator(long staleAfterMillis) {
    this(staleAfterMillis, (role, instance) -> Optional.empty());
  }

  SwarmWorkersAggregator(long staleAfterMillis,
                         BiFunction<String, String, Optional<String>> runtimeBeeIdResolver) {
    this.staleAfterMillis = staleAfterMillis;
    this.runtimeBeeIdResolver = Objects.requireNonNull(runtimeBeeIdResolver, "runtimeBeeIdResolver");
  }

  void updateFromWorkerStatus(String role, String instance, JsonNode dataNode, JsonNode runtimeNode) {
    if (role == null || role.isBlank() || instance == null || instance.isBlank()) {
      return;
    }
    if (dataNode == null || !dataNode.isObject()) {
      return;
    }

    String key = key(role, instance);
    WorkerSnapshot previous = byKey.get(key);
    boolean enabled = dataNode.path("enabled").asBoolean(true);
    long tps = dataNode.path("tps").asLong(0L);

    JsonNode ioStateNode = dataNode.path("ioState").path("work");
    String input = ioStateNode.path("input").asText("unknown");
    String output = ioStateNode.path("output").asText("unknown");
    Map<String, Object> runtime = runtimeFrom(runtimeNode);
    if (runtime == null && previous != null) {
      runtime = previous.runtime();
    }
    Map<String, Object> config = configFrom(dataNode, previous);
    String workerBeeIdEcho = workerBeeIdEchoFrom(dataNode);

    long now = System.currentTimeMillis();
    WorkerSnapshot snapshot = new WorkerSnapshot(
        role,
        instance,
        enabled,
        tps,
        input,
        output,
        runtime,
        config,
        workerBeeIdEcho,
        Instant.ofEpochMilli(now).toString(),
        now);
    byKey.put(key, snapshot);
  }

  List<Map<String, Object>> snapshot() {
    if (byKey.isEmpty()) {
      return List.of();
    }
    long now = System.currentTimeMillis();
    List<WorkerSnapshot> snapshots = new ArrayList<>(byKey.values());
    snapshots.sort(Comparator.comparing(WorkerSnapshot::role).thenComparing(WorkerSnapshot::instance));

    List<Map<String, Object>> out = new ArrayList<>(snapshots.size());
    for (WorkerSnapshot snapshot : snapshots) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("role", snapshot.role());
      entry.put("instance", snapshot.instance());
      Optional<String> runtimeBeeId = resolveRuntimeBeeId(snapshot.role(), snapshot.instance());
      runtimeBeeId.ifPresent(beeId -> entry.put(WORKER_BEE_ID_FIELD, beeId));
      entry.put("enabled", snapshot.enabled());
      entry.put("tps", snapshot.tps());
      entry.put("lastSeenAt", snapshot.lastSeenAt());
      entry.put("stale", now - snapshot.lastSeenMillis() > staleAfterMillis);
      entry.put("ioState", Map.of(
          "work", Map.of(
              "input", snapshot.workInput(),
              "output", snapshot.workOutput()
          )
      ));
      if (snapshot.runtime() != null && !snapshot.runtime().isEmpty()) {
        entry.put("runtime", snapshot.runtime());
      }
      if (snapshot.config() != null) {
        entry.put("config", snapshot.config());
      }
      identityDiagnostics(runtimeBeeId, snapshot.workerBeeIdEcho())
          .ifPresent(diagnostics -> entry.put(IDENTITY_DIAGNOSTICS_FIELD, diagnostics));
      out.add(entry);
    }
    return List.copyOf(out);
  }

  void clear() {
    byKey.clear();
  }

  private static String key(String role, String instance) {
    return Objects.requireNonNull(role).trim() + ":" + Objects.requireNonNull(instance).trim();
  }

  private Optional<String> resolveRuntimeBeeId(String role, String instance) {
    Optional<String> resolved = runtimeBeeIdResolver.apply(role, instance);
    if (resolved == null) {
      return Optional.empty();
    }
    return resolved.map(String::trim).filter(value -> !value.isBlank());
  }

  private static Optional<Map<String, Object>> identityDiagnostics(Optional<String> runtimeBeeId,
                                                                   String workerBeeIdEcho) {
    if (runtimeBeeId == null || runtimeBeeId.isEmpty()) {
      return Optional.empty();
    }
    String expected = runtimeBeeId.get();
    String actual = normalize(workerBeeIdEcho);
    if (actual == null) {
      Map<String, Object> diagnostics = new LinkedHashMap<>();
      diagnostics.put(WORKER_BEE_ID_ECHO_FIELD, WORKER_BEE_ID_ECHO_MISSING);
      diagnostics.put(EXPECTED_BEE_ID_FIELD, expected);
      return Optional.of(diagnostics);
    }
    if (!expected.equals(actual)) {
      Map<String, Object> diagnostics = new LinkedHashMap<>();
      diagnostics.put(WORKER_BEE_ID_ECHO_FIELD, WORKER_BEE_ID_ECHO_MISMATCH);
      diagnostics.put(EXPECTED_BEE_ID_FIELD, expected);
      diagnostics.put(ACTUAL_BEE_ID_FIELD, actual);
      return Optional.of(diagnostics);
    }
    return Optional.empty();
  }

  private static Map<String, Object> runtimeFrom(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("templateId", textOrNull(node, "templateId"));
    runtime.put("runId", textOrNull(node, "runId"));
    runtime.put("containerId", textOrNull(node, "containerId"));
    runtime.put("image", textOrNull(node, "image"));
    runtime.put("stackName", textOrNull(node, "stackName"));
    if (runtime.values().stream().allMatch(Objects::isNull)) {
      return null;
    }
    return runtime;
  }

  private static Map<String, Object> configFrom(JsonNode dataNode, WorkerSnapshot previous) {
    if (dataNode != null && dataNode.has("config")) {
      JsonNode configNode = dataNode.get("config");
      if (configNode == null || !configNode.isObject()) {
        return Map.of();
      }
      return objectFrom(configNode);
    }
    return previous != null ? previous.config() : null;
  }

  private static String workerBeeIdEchoFrom(JsonNode dataNode) {
    if (dataNode == null || !dataNode.isObject()) {
      return null;
    }
    JsonNode context = dataNode.path("context");
    if (!context.isObject()) {
      return null;
    }
    return normalize(textOrNull(context, WORKER_BEE_ID_FIELD));
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private static Map<String, Object> objectFrom(JsonNode node) {
    Map<String, Object> values = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> values.put(entry.getKey(), valueFrom(entry.getValue())));
    return Collections.unmodifiableMap(values);
  }

  private static List<Object> listFrom(JsonNode node) {
    List<Object> values = new ArrayList<>();
    node.elements().forEachRemaining(element -> values.add(valueFrom(element)));
    return Collections.unmodifiableList(values);
  }

  private static Object valueFrom(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      return objectFrom(node);
    }
    if (node.isArray()) {
      return listFrom(node);
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isNumber()) {
      return node.numberValue();
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    return node.asText();
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null || field == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private record WorkerSnapshot(
      String role,
      String instance,
      boolean enabled,
      long tps,
      String workInput,
      String workOutput,
      Map<String, Object> runtime,
      Map<String, Object> config,
      String workerBeeIdEcho,
      String lastSeenAt,
      long lastSeenMillis) {
  }
}
