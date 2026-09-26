package io.pockethive.swarmcontroller;

import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Clock;
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
 * Contract: RESP-SWARM-OBSERVATION — docs/architecture/runtime-responsibilities.md#resp-swarm-observation.
 */
public final class SwarmReadinessTracker {

  private static final Logger log = LoggerFactory.getLogger(SwarmReadinessTracker.class);

  private static final long STATUS_TTL_MS = 15_000L;

  private final WorkerStatusRequestCallback statusRequestCallback;
  private final Clock clock;

  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final ConcurrentMap<Target, Long> lastSeen = new ConcurrentHashMap<>();
  private final AtomicLong statusObservationRevision = new AtomicLong();
  private final ConcurrentMap<Target, WorkerSnapshotObservation> lastSnapshot = new ConcurrentHashMap<>();
  private final ConcurrentMap<Target, Boolean> enabled = new ConcurrentHashMap<>();

  public SwarmReadinessTracker(WorkerStatusRequestCallback statusRequestCallback) {
    this(statusRequestCallback, Clock.systemUTC());
  }

  SwarmReadinessTracker(WorkerStatusRequestCallback statusRequestCallback, Clock clock) {
    this.statusRequestCallback = Objects.requireNonNull(statusRequestCallback, "statusRequestCallback");
    this.clock = Objects.requireNonNull(clock, "clock");
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

  public synchronized void recordHeartbeat(String role, String instance, long timestamp) {
    if (!hasText(role) || !hasText(instance)) {
      return;
    }
    lastSeen.put(key(role, instance), timestamp);
  }

  public synchronized void recordStatusSnapshot(String role, String instance, boolean enabledFlag) {
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

  public synchronized void recordEnabled(String role, String instance, boolean flag) {
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
        Target key = key(role, instance);
        WorkerSnapshotObservation observation = lastSnapshot.get(key);
        if (observation == null || observation.revision() <= observationRevision) {
          return false;
        }
      }
    }
    return true;
  }

  /** Returns every expected worker lacking post-dispatch evidence for the requested enablement. */
  public List<Target> nonConvergedWorkersAfter(
      long observationRevision, boolean expectedEnabled, List<Target> expectedWorkers) {
    List<Target> result = new ArrayList<>();
    expectedWorkers.forEach(workerKey -> {
      WorkerSnapshotObservation observation = lastSnapshot.get(workerKey);
      if (observation == null || observation.revision() <= observationRevision
          || observation.enabled() != expectedEnabled) {
        result.add(workerKey);
      }
    });
    result.sort(java.util.Comparator.comparing(Target::role)
        .thenComparing(Target::instance));
    return List.copyOf(result);
  }

  public synchronized Map<Target, WorkerObservation> workerObservations() {
    long now = clock.millis();
    Map<Target, WorkerObservation> observations = new HashMap<>();
    lastSeen.forEach((target, timestamp) -> observations.put(target,
        new WorkerObservation(Instant.ofEpochMilli(timestamp),
            enabled.getOrDefault(target, false), isStale(timestamp, now))));
    return Map.copyOf(observations);
  }

  public synchronized SwarmMetrics metrics() {
    int desired = expectedReady.values().stream().mapToInt(Integer::intValue).sum();
    var observations = workerObservations().values();
    int healthy = 0;
    int running = 0;
    int enabledCount = 0;
    Instant watermark = Instant.ofEpochMilli(clock.millis());
    boolean first = true;
    for (WorkerObservation observation : observations) {
      if (first || observation.lastSeenAt().isBefore(watermark)) {
        watermark = observation.lastSeenAt();
        first = false;
      }
      if (!observation.stale()) healthy++;
      if (observation.enabled()) {
        enabledCount++;
        if (!observation.stale()) running++;
      }
    }
    return new SwarmMetrics(desired, healthy, running, enabledCount, watermark);
  }

  private static boolean isStale(long timestamp, long now) {
    return now - timestamp > STATUS_TTL_MS;
  }

  private synchronized boolean isFullyReady() {
    long now = clock.millis();
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
        if (isStale(ts, now)) {
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

  private static Target key(String role, String instance) {
    return new Target(role, instance);
  }

  private record WorkerSnapshotObservation(long revision, boolean enabled) {
  }
}
