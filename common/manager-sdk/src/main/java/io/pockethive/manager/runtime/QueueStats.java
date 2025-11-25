package io.pockethive.manager.runtime;

import java.util.OptionalLong;

/**
 * Snapshot of queue depth/consumers and, optionally, age of the oldest message.
 */
public record QueueStats(long depth, int consumers, OptionalLong oldestAgeSeconds) {

  public static QueueStats empty() {
    return new QueueStats(0L, 0, OptionalLong.empty());
  }
}

