package io.pockethive.manager.ports;

import io.pockethive.manager.runtime.QueueStats;

/**
 * Abstraction for metrics updates emitted by the manager runtime core.
 */
public interface MetricsPort {

  /**
   * Update queue metrics for a single queue.
   *
   * @param queueName queue name
   * @param stats     latest stats
   */
  void updateQueueMetrics(String queueName, QueueStats stats);
}

