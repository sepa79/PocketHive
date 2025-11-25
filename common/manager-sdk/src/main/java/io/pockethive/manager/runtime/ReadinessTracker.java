package io.pockethive.manager.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks readiness and basic worker metrics for a managed swarm/topology.
 * <p>
 * This class is a transport-agnostic variant of the Swarm Controller's
 * readiness bookkeeping. It can be reused by different manager implementations
 * (Swarm Controller, future controllers, tools).
 */
public final class ReadinessTracker {

  @FunctionalInterface
  public interface StatusRequestCallback {
    void requestStatus(String role, String instance, String reason);
  }

  private static final Logger log = LoggerFactory.getLogger(ReadinessTracker.class);

  private static final long STATUS_TTL_MS = 15_000L;

  private final StatusRequestCallback statusRequestCallback;

  private final Map<String, Integer> expectedReady = new HashMap<>();
  private final Map<String, List<String>> instancesByRole = new HashMap<>();
  private final ConcurrentMap<String, Long> lastSeen = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, Boolean> enabled = new ConcurrentHashMap<>();

  public ReadinessTracker(StatusRequestCallback statusRequestCallback) {
    this.statusRequestCallback = Objects.requireNonNull(statusRequestCallback, "statusRequestCallback");
  }

  public synchronized void reset() {
    expectedReady.clear();
    instancesByRole.clear();
    lastSeen.clear();
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

  public ManagerMetrics metrics() {
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
    return new ManagerMetrics(desired, healthy, running, enabledCount, watermark);
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

