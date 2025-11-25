package io.pockethive.manager.ports;

import io.pockethive.manager.runtime.QueueStats;

/**
 * Port used by the manager runtime core to read queue statistics.
 */
public interface QueueStatsPort {

  /**
   * Return a snapshot of queue statistics for the given queue name.
   *
   * @param queueName logical queue name
   * @return queue stats (never null)
   */
  QueueStats getQueueStats(String queueName);
}

