package io.pockethive.manager.runtime;

import java.util.Map;

/**
 * Transport-agnostic lifecycle contract for a manager controlling a swarm or topology.
 */
public interface ManagerLifecycle {

  void prepare(String planJson);

  void start(String planJson);

  void stop();

  void remove();

  ManagerStatus getStatus();

  boolean markReady(String role, String instance);

  void updateHeartbeat(String role, String instance);

  void updateEnabled(String role, String instance, boolean enabled);

  ManagerMetrics getMetrics();

  Map<String, QueueStats> snapshotQueueStats();

  void enableAll();

  void setWorkEnabled(boolean enabled);

  void setManagerEnabled(boolean enabled);

  boolean isReadyForWork();
}

