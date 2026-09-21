package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.topology.work.ResolvedWorkTopology;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/**
 * Responsibility: Read one immutable queue-stat snapshot and update its matching queue gauges.
 * Must not: Declare/delete queues, derive lifecycle state, or interpret scenario plans.
 * Addresses come from the supplied resolved topology.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 * Behavior: Return one stat entry per distinct resolved input address.
 */
public final class SwarmQueueStatsCollector {

  private final QueueStatsPort queueStats;
  private final SwarmQueueMetrics queueMetrics;

  public SwarmQueueStatsCollector(
      QueueStatsPort queueStats,
      SwarmQueueMetrics queueMetrics) {
    this.queueStats = Objects.requireNonNull(queueStats, "queueStats");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
  }

  public Map<String, QueueStats> snapshot(ResolvedWorkTopology topology) {
    Objects.requireNonNull(topology, "topology");
    Set<String> queueNames = new LinkedHashSet<>(topology.channels().size());
    topology.channels().values().stream().map(channel -> channel.inputAddress()).forEach(queueNames::add);
    Map<String, QueueStats> snapshot = new LinkedHashMap<>(queueNames.size());
    for (String queueName : queueNames) {
      QueueStats stats = queueStats.getQueueStats(queueName);
      snapshot.put(queueName, stats);
      queueMetrics.update(queueName, stats);
    }
    return Map.copyOf(snapshot);
  }
}
