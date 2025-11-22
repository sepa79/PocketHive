package io.pockethive.manager.runtime;

import io.pockethive.manager.ports.Clock;
import io.pockethive.manager.ports.ControlPlanePort;
import io.pockethive.manager.ports.MetricsPort;
import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.ports.WorkTopologyPort;
import io.pockethive.manager.ports.WorkloadPort;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal, transport-agnostic core implementation of {@link ManagerLifecycle}.
 * <p>
 * This class holds the manager's high-level lifecycle state, readiness counters,
 * and queue metrics wiring through small ports. It deliberately avoids any
 * framework or transport classes so that different manager implementations
 * (Swarm Controller, future controllers, tools) can share the same core.
 * <p>
 * NOTE: At this stage the core does not yet orchestrate container/queue
 * provisioning directly; that logic will be migrated incrementally from
 * existing controllers. For now it provides a clean API surface and basic
 * bookkeeping that callers can build on.
 */
public final class ManagerRuntimeCore implements ManagerLifecycle {

  private final WorkTopologyPort topology;
  private final WorkloadPort workload;
  private final ControlPlanePort controlPlane;
  private final QueueStatsPort queueStats;
  private final MetricsPort metricsPort;
  private final Clock clock;
  private final String swarmId;
  private final String role;
  private final String instanceId;

  private final ReadinessTracker readinessTracker;

  private volatile ManagerStatus status = ManagerStatus.NEW;
  private volatile boolean managerEnabled = false;
  private volatile boolean workEnabled = false;
  private volatile String lastPlanJson;

  private final Set<String> trackedQueues = new LinkedHashSet<>();
  private volatile ManagerMetrics metricsSnapshot = new ManagerMetrics(0, 0, 0, 0, 0L);

  public ManagerRuntimeCore(WorkTopologyPort topology,
                            WorkloadPort workload,
                            ControlPlanePort controlPlane,
                            QueueStatsPort queueStats,
                            MetricsPort metricsPort,
                            Clock clock,
                            String swarmId,
                            String role,
                            String instanceId) {
    this.topology = Objects.requireNonNull(topology, "topology");
    this.workload = Objects.requireNonNull(workload, "workload");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.queueStats = Objects.requireNonNull(queueStats, "queueStats");
    this.metricsPort = Objects.requireNonNull(metricsPort, "metricsPort");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.swarmId = requireText(swarmId, "swarmId");
    this.role = requireText(role, "role");
    this.instanceId = requireText(instanceId, "instanceId");
    this.readinessTracker = new ReadinessTracker((r, inst, reason) -> {
      // ManagerRuntimeCore does not send status-requests itself yet; callers
      // can wire this callback to a control-plane client if needed.
    });
  }

  @Override
  public synchronized void prepare(String planJson) {
    readinessTracker.reset();
    this.lastPlanJson = planJson;
    if (status == ManagerStatus.NEW || status == ManagerStatus.FAILED || status == ManagerStatus.REMOVED) {
      status = ManagerStatus.PREPARING;
    }
  }

  @Override
  public synchronized void start(String planJson) {
    if (lastPlanJson == null) {
      prepare(planJson);
    } else if (planJson != null && !planJson.isBlank()) {
      lastPlanJson = planJson;
    }
    workEnabled = true;
    managerEnabled = true;
    status = ManagerStatus.RUNNING;
  }

  @Override
  public synchronized void stop() {
    workEnabled = false;
    status = ManagerStatus.STOPPED;
  }

  @Override
  public synchronized void remove() {
    workEnabled = false;
    status = ManagerStatus.REMOVED;
  }

  @Override
  public synchronized ManagerStatus getStatus() {
    return status;
  }

  @Override
  public synchronized boolean markReady(String role, String instance) {
    boolean ready = readinessTracker.markReady(role, instance);
    updateEnabled(role, instance, true);
    updateHeartbeat(role, instance);
    return ready;
  }

  @Override
  public synchronized void updateHeartbeat(String role, String instance) {
    String r = requireText(role, "role");
    String inst = requireText(instance, "instance");
    readinessTracker.recordHeartbeat(r, inst, clock.currentTimeMillis());
  }

  @Override
  public synchronized void updateEnabled(String role, String instance, boolean enabled) {
    String r = requireText(role, "role");
    String inst = requireText(instance, "instance");
    readinessTracker.recordEnabled(r, inst, enabled);
    recomputeMetrics();
  }

  @Override
  public synchronized ManagerMetrics getMetrics() {
    return metricsSnapshot;
  }

  @Override
  public synchronized Map<String, QueueStats> snapshotQueueStats() {
    if (trackedQueues.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, QueueStats> snapshot = new LinkedHashMap<>(trackedQueues.size());
    for (String queue : trackedQueues) {
      QueueStats stats = queueStats.getQueueStats(queue);
      snapshot.put(queue, stats);
      metricsPort.updateQueueMetrics(queue, stats);
    }
    return Collections.unmodifiableMap(snapshot);
  }

  @Override
  public synchronized void enableAll() {
    workEnabled = true;
    status = ManagerStatus.RUNNING;
  }

  @Override
  public synchronized void setWorkEnabled(boolean enabled) {
    this.workEnabled = enabled;
    if (!enabled && status == ManagerStatus.RUNNING) {
      status = ManagerStatus.STOPPED;
    } else if (enabled && status == ManagerStatus.STOPPED) {
      status = ManagerStatus.RUNNING;
    }
  }

  @Override
  public synchronized void setManagerEnabled(boolean enabled) {
    this.managerEnabled = enabled;
  }

  @Override
  public synchronized boolean isReadyForWork() {
    if (!managerEnabled || !workEnabled) {
      return false;
    }
    if (status != ManagerStatus.RUNNING) {
      return false;
    }
    return readinessTracker.isReadyForWork();
  }

  /**
   * Configure which queues should be tracked for metrics and snapshots.
   */
  public synchronized void setTrackedQueues(Set<String> queues) {
    trackedQueues.clear();
    if (queues != null && !queues.isEmpty()) {
      for (String q : queues) {
        if (q != null && !q.isBlank()) {
          trackedQueues.add(q);
        }
      }
    }
  }

  private void recomputeMetrics() {
    this.metricsSnapshot = readinessTracker.metrics();
  }

  private static String requireText(String value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return trimmed;
  }
}
