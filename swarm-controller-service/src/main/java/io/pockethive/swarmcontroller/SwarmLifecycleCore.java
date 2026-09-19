package io.pockethive.swarmcontroller;

import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Responsibility: Define the required command, observation, and configuration capabilities of the Controller core.
 * Must not: Provide fallback behavior or expose transport-specific operations.
 * Contract: Every implementation supplies one authoritative lifecycle and worker-observation state owner.
 */
public interface SwarmLifecycleCore {

  void prepare(String templateJson);

  void applyScenarioPlan(String planJson);

  void start(String planJson);

  void stop();

  List<RemoveResource> remove();

  WorkloadState getWorkloadState();

  boolean markReady(String role, String instance);

  void updateHeartbeat(String role, String instance);

  void recordStatusSnapshot(String role, String instance, boolean enabled);

  long workerStatusObservationRevision();

  boolean hasWorkerStatusSnapshotsAfter(long observationRevision);

  List<Target> nonConvergedWorkersAfter(long observationRevision, boolean expectedEnabled);

  void updateEnabled(String role, String instance, boolean enabled);

  SwarmMetrics getMetrics();

  List<Target> expectedWorkers();

  Map<String, QueueStats> snapshotQueueStats();

  Map<String, Object> workBindingsSnapshot();

  void enableAll();

  void setSwarmEnabled(boolean enabled);

  boolean isReadyForWork();

  TrafficPolicy trafficPolicy();

  Optional<String> handleConfigUpdateError(String role, String instance, String error);

  String sutId();

  void fail(String reason);

  boolean hasPendingConfigUpdates();

  void setControllerEnabled(boolean enabled);

  void resetScenarioPlan();

  void setScenarioRuns(Integer runs);
}
