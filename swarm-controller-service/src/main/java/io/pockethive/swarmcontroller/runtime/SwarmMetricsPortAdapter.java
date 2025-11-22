package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.MetricsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import java.util.Objects;

/**
 * Adapter that bridges {@link MetricsPort} to {@link SwarmQueueMetrics}.
 */
public final class SwarmMetricsPortAdapter implements MetricsPort {

  private final SwarmQueueMetrics queueMetrics;

  public SwarmMetricsPortAdapter(SwarmQueueMetrics queueMetrics) {
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
  }

  @Override
  public void updateQueueMetrics(String queueName, QueueStats stats) {
    io.pockethive.swarmcontroller.QueueStats controllerStats =
        new io.pockethive.swarmcontroller.QueueStats(stats.depth(), stats.consumers(), stats.oldestAgeSeconds());
    queueMetrics.update(queueName, controllerStats);
  }
}

