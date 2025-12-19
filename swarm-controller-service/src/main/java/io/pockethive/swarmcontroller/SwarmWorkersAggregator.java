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

    boolean enabled = dataNode.path("enabled").asBoolean(true);
    long tps = dataNode.path("tps").asLong(0L);

    JsonNode ioStateNode = dataNode.path("ioState").path("work");
    String input = ioStateNode.path("input").asText("unknown");
    String output = ioStateNode.path("output").asText("unknown");

    long now = System.currentTimeMillis();
    WorkerSnapshot snapshot = new WorkerSnapshot(
        role,
        instance,
        enabled,
        tps,
        input,
        output,
        Instant.ofEpochMilli(now).toString(),
        now);
    byKey.put(key(role, instance), snapshot);
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

  private record WorkerSnapshot(
      String role,
      String instance,
      boolean enabled,
      long tps,
      String workInput,
      String workOutput,
      String lastSeenAt,
      long lastSeenMillis) {
  }
}

