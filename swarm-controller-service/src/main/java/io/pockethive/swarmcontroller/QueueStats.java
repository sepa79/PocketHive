package io.pockethive.swarmcontroller;

import java.util.OptionalLong;

/**
 * Snapshot of RabbitMQ queue statistics exposed via {@link SwarmLifecycle}.
 *
 * @param depth number of messages currently awaiting consumption
 * @param consumers number of active consumers subscribed to the queue
 * @param oldestAgeSec optional age in seconds of the oldest message if the broker reported it
 */
public record QueueStats(long depth, int consumers, OptionalLong oldestAgeSec) {

  public QueueStats {
    if (oldestAgeSec == null) {
      oldestAgeSec = OptionalLong.empty();
    }
  }

  /**
   * Convenience constructor for empty statistics when a queue is missing or provides no metrics.
   */
  public QueueStats() {
    this(0L, 0, OptionalLong.empty());
  }

  /**
   * Create a zero-value snapshot.
   */
  public static QueueStats empty() {
    return new QueueStats();
  }
}
