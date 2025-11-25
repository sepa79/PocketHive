package io.pockethive.manager.scenario;

import io.pockethive.manager.runtime.ManagerMetrics;
import io.pockethive.manager.runtime.ManagerStatus;
import io.pockethive.manager.runtime.QueueStats;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only snapshot of the manager runtime, passed to scenarios on each tick.
 */
public final class ManagerRuntimeView {

  private final ManagerStatus status;
  private final ManagerMetrics metrics;
  private final Map<String, QueueStats> queueStats;

  public ManagerRuntimeView(ManagerStatus status,
                            ManagerMetrics metrics,
                            Map<String, QueueStats> queueStats) {
    this.status = Objects.requireNonNull(status, "status");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.queueStats = queueStats == null
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(queueStats);
  }

  public ManagerStatus status() {
    return status;
  }

  public ManagerMetrics metrics() {
    return metrics;
  }

  public Map<String, QueueStats> queueStats() {
    return queueStats;
  }
}

