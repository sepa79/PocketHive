package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates per-worker status deltas into a swarm-level worker list snapshot.
 *
 * <p>This is intended for Swarm Controller {@code status-full} publishing so UIs
 * can avoid subscribing directly to worker status fan-out.</p>
 */
final class SwarmWorkersAggregator {

  private final long staleAfterMillis;
  private final Map<String, WorkerSnapshot> byKey = new ConcurrentHashMap<>();

  SwarmWorkersAggregator(long staleAfterMillis) {
    this.staleAfterMillis = staleAfterMillis;
  }

  void updateFromWorkerStatus(String role, String instance, JsonNode dataNode) {
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
    Map<String, Object> runtime = runtimeFrom(dataNode.path("runtime"));
    if (runtime == null && previous != null) {
      runtime = previous.runtime();
    }

    long now = System.currentTimeMillis();
    WorkerSnapshot snapshot = new WorkerSnapshot(
        role,
        instance,
        enabled,
        tps,
        input,
        output,
        runtime,
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

  private static Map<String, Object> runtimeFrom(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("runId", textOrNull(node, "runId"));
    runtime.put("containerId", textOrNull(node, "containerId"));
    runtime.put("image", textOrNull(node, "image"));
    runtime.put("stackName", textOrNull(node, "stackName"));
    if (runtime.values().stream().allMatch(Objects::isNull)) {
      return null;
    }
    return runtime;
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
      String lastSeenAt,
      long lastSeenMillis) {
  }
}
