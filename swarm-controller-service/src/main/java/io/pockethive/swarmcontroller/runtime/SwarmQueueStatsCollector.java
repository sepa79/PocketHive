package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: Read one immutable queue-stat snapshot and update its matching queue gauges.
 * Must not: Declare/delete queues, derive lifecycle state, or interpret scenario plans.
 * Contract: Resolve queue names canonically and return one stat entry for every distinct supplied suffix.
 */
public final class SwarmQueueStatsCollector {

  private final SwarmControllerProperties properties;
  private final QueueStatsPort queueStats;
  private final SwarmQueueMetrics queueMetrics;

  public SwarmQueueStatsCollector(
      SwarmControllerProperties properties,
      QueueStatsPort queueStats,
      SwarmQueueMetrics queueMetrics) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.queueStats = Objects.requireNonNull(queueStats, "queueStats");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
  }

  public Map<String, QueueStats> snapshot(Set<String> queueSuffixes) {
    Objects.requireNonNull(queueSuffixes, "queueSuffixes");
    Set<String> queueNames = new LinkedHashSet<>(queueSuffixes.size());
    queueSuffixes.stream().map(properties::queueName).forEach(queueNames::add);
    Map<String, QueueStats> snapshot = new LinkedHashMap<>(queueNames.size());
    for (String queueName : queueNames) {
      QueueStats stats = queueStats.getQueueStats(queueName);
      snapshot.put(queueName, stats);
      queueMetrics.update(queueName, stats);
    }
    return Map.copyOf(snapshot);
  }
}
