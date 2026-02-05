package io.pockethive.swarmcontroller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

  /**
   * Tracks swarm-level readiness and basic worker metrics.
   * <p>
   * This component owns the bookkeeping for expected vs. ready workers, heartbeats,
   * status-full snapshots, and enabled flags. It can request status on missing/stale
   * heartbeats via a callback; snapshot freshness checks must remain side-effect free
   * to avoid status-request storms.
   */
  public final class SwarmReadinessTracker {

  public interface StatusRequestCallback {
    void requestStatus(String role, String instance, String reason);
  }

  private static final Logger log = LoggerFactory.getLogger(SwarmReadinessTracker.class);

  private static final long STATUS_TTL_MS = 15_000L;

  private final StatusRequestCallback statusRequestCallback;

  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final ConcurrentMap<String, Long> lastSeen = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Long> lastSnapshotSeen = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> enabled = new ConcurrentHashMap<>();

  public SwarmReadinessTracker(StatusRequestCallback statusRequestCallback) {
    this.statusRequestCallback = Objects.requireNonNull(statusRequestCallback, "statusRequestCallback");
  }

  public synchronized void reset() {
    expectedReady.clear();
    instancesByRole.clear();
    lastSeen.clear();
    lastSnapshotSeen.clear();
    enabled.clear();
  }

  public synchronized void registerExpected(String role) {
    if (role == null || role.isBlank()) {
      return;
    }
    expectedReady.merge(role, 1, Integer::sum);
  }

  public void recordHeartbeat(String role, String instance, long timestamp) {
    if (!hasText(role) || !hasText(instance)) {
      return;
    }
    lastSeen.put(key(role, instance), timestamp);
  }

  public void recordStatusSnapshot(String role, String instance, long timestamp) {
    if (!hasText(role) || !hasText(instance)) {
      return;
    }
    lastSnapshotSeen.put(key(role, instance), timestamp);
  }

  public void recordEnabled(String role, String instance, boolean flag) {
    if (!hasText(role) || !hasText(instance)) {
      return;
    }
    enabled.put(key(role, instance), flag);
  }

  public synchronized boolean markReady(String role, String instance) {
    if (!hasText(role) || !hasText(instance)) {
      return isReadyForWork();
    }
    instancesByRole.computeIfAbsent(role, r -> new ArrayList<>());
    List<String> instances = instancesByRole.get(role);
    if (!instances.contains(instance)) {
      instances.add(instance);
      log.info("bee {} of role {} marked ready", instance, role);
    }
    return isFullyReady();
  }

  public synchronized boolean isReadyForWork() {
    if (expectedReady.isEmpty()) {
      return true;
    }
    return isFullyReady();
  }

  public boolean hasFreshSnapshotsSince(long cutoffMillis) {
    Map<String, List<String>> snapshot;
    synchronized (this) {
      snapshot = new HashMap<>(instancesByRole);
    }
    if (snapshot.isEmpty()) {
      return true;
    }
    for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
      String role = entry.getKey();
      for (String instance : entry.getValue()) {
        String key = key(role, instance);
        Long ts = lastSnapshotSeen.get(key);
        if (ts == null || ts < cutoffMillis) {
          return false;
        }
      }
    }
    return true;
  }

  public SwarmMetrics metrics() {
    int desired;
    Map<String, Integer> expectedSnapshot;
    synchronized (this) {
      desired = expectedReady.values().stream().mapToInt(Integer::intValue).sum();
      expectedSnapshot = new HashMap<>(expectedReady);
    }
    long now = System.currentTimeMillis();
    int healthy = 0;
    int running = 0;
    int enabledCount = 0;
    long watermark = Long.MAX_VALUE;
    for (Map.Entry<String, Long> e : lastSeen.entrySet()) {
      long ts = e.getValue();
      if (ts < watermark) {
        watermark = ts;
      }
      boolean isHealthy = now - ts <= STATUS_TTL_MS;
      if (isHealthy) {
        healthy++;
      }
      boolean en = enabled.getOrDefault(e.getKey(), false);
      if (en) {
        enabledCount++;
        if (isHealthy) {
          running++;
        }
      }
    }
    if (watermark == Long.MAX_VALUE) {
      watermark = now;
    }
    return new SwarmMetrics(desired, healthy, running, enabledCount, Instant.ofEpochMilli(watermark));
  }

  private synchronized boolean isFullyReady() {
    long now = System.currentTimeMillis();
    for (Map.Entry<String, Integer> e : expectedReady.entrySet()) {
      String role = e.getKey();
      List<String> ready = instancesByRole.getOrDefault(role, List.of());
      if (ready.size() < e.getValue()) {
        return false;
      }
      for (String inst : ready) {
        Long ts = lastSeen.get(key(role, inst));
        if (ts == null) {
          log.info("Requesting status for {}.{} because no heartbeat was recorded yet", role, inst);
          statusRequestCallback.requestStatus(role, inst, "missing-heartbeat");
          return false;
        }
        long age = now - ts;
        if (age > STATUS_TTL_MS) {
          log.info(
              "Requesting status for {}.{} because heartbeat is stale (age={}ms, ttl={}ms)",
              role,
              inst,
              age,
              STATUS_TTL_MS);
          statusRequestCallback.requestStatus(role, inst, "stale-heartbeat");
          return false;
        }
      }
    }
    return !expectedReady.isEmpty();
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String key(String role, String instance) {
    return role + "." + instance;
  }
}
