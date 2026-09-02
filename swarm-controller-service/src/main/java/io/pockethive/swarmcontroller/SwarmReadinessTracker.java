package io.pockethive.swarmcontroller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: Own swarm readiness, worker heartbeat, enablement, and status observation ordering.
 * Must not: Decode messages, publish lifecycle outcomes, or infer observation order from wall-clock timestamps.
 * Contract: Each accepted status-full advances one monotonic revision used by convergence checks.
 */
public final class SwarmReadinessTracker {

  private static final Logger log = LoggerFactory.getLogger(SwarmReadinessTracker.class);

  private static final long STATUS_TTL_MS = 15_000L;

  private final WorkerStatusRequestCallback statusRequestCallback;

  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final ConcurrentMap<String, Long> lastSeen = new ConcurrentHashMap<>();
  private final AtomicLong statusObservationRevision = new AtomicLong();
  private final ConcurrentMap<String, WorkerSnapshotObservation> lastSnapshot = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> enabled = new ConcurrentHashMap<>();

  public SwarmReadinessTracker(WorkerStatusRequestCallback statusRequestCallback) {
    this.statusRequestCallback = Objects.requireNonNull(statusRequestCallback, "statusRequestCallback");
  }

  public synchronized void reset() {
    expectedReady.clear();
    instancesByRole.clear();
    lastSeen.clear();
    lastSnapshot.clear();
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

  public void recordStatusSnapshot(String role, String instance, boolean enabledFlag) {
    if (!hasText(role) || !hasText(instance)) {
      return;
    }
    long revision = statusObservationRevision.incrementAndGet();
    lastSnapshot.put(key(role, instance), new WorkerSnapshotObservation(revision, enabledFlag));
    enabled.put(key(role, instance), enabledFlag);
  }

  public long statusObservationRevision() {
    return statusObservationRevision.get();
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

  public boolean hasSnapshotsAfter(long observationRevision) {
    Map<String, List<String>> snapshot = instancesSnapshot();
    if (snapshot.isEmpty()) {
      return true;
    }
    for (Map.Entry<String, List<String>> entry : snapshot.entrySet()) {
      String role = entry.getKey();
      for (String instance : entry.getValue()) {
        String key = key(role, instance);
        WorkerSnapshotObservation observation = lastSnapshot.get(key);
        if (observation == null || observation.revision() <= observationRevision) {
          return false;
        }
      }
    }
    return true;
  }

  /** Returns every expected worker lacking post-dispatch evidence for the requested enablement. */
  public List<io.pockethive.swarm.model.lifecycle.Target> nonConvergedWorkersAfter(
      long observationRevision, boolean expectedEnabled) {
    Map<String, List<String>> snapshot = instancesSnapshot();
    List<io.pockethive.swarm.model.lifecycle.Target> result = new ArrayList<>();
    snapshot.forEach((role, instances) -> instances.forEach(instance -> {
      String workerKey = key(role, instance);
      WorkerSnapshotObservation observation = lastSnapshot.get(workerKey);
      if (observation == null || observation.revision() <= observationRevision
          || observation.enabled() != expectedEnabled) {
        result.add(new io.pockethive.swarm.model.lifecycle.Target(role, instance));
      }
    }));
    result.sort(java.util.Comparator.comparing(io.pockethive.swarm.model.lifecycle.Target::role)
        .thenComparing(io.pockethive.swarm.model.lifecycle.Target::instance));
    return List.copyOf(result);
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

  private synchronized Map<String, List<String>> instancesSnapshot() {
    Map<String, List<String>> snapshot = new HashMap<>();
    instancesByRole.forEach((role, instances) -> snapshot.put(role, List.copyOf(instances)));
    return Map.copyOf(snapshot);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String key(String role, String instance) {
    return role + "." + instance;
  }

  private record WorkerSnapshotObservation(long revision, boolean enabled) {
  }
}
