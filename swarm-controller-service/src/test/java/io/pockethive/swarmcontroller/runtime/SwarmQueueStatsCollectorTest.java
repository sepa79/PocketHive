package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.manager.ports.QueueStatsPort;
import io.pockethive.manager.runtime.QueueStats;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SwarmQueueStatsCollectorTest {

  @Test
  void collectsEveryResolvedQueueAndUpdatesItsMatchingMetrics() {
    SwarmControllerProperties properties = mock(SwarmControllerProperties.class);
    QueueStatsPort queueStats = mock(QueueStatsPort.class);
    SwarmQueueMetrics queueMetrics = mock(SwarmQueueMetrics.class);
    QueueStats inputStats = new QueueStats(5, 2, OptionalLong.of(17));
    QueueStats outputStats = QueueStats.empty();
    when(properties.queueName("input")).thenReturn("ph.swarm-1.input");
    when(properties.queueName("output")).thenReturn("ph.swarm-1.output");
    when(queueStats.getQueueStats("ph.swarm-1.input")).thenReturn(inputStats);
    when(queueStats.getQueueStats("ph.swarm-1.output")).thenReturn(outputStats);
    SwarmQueueStatsCollector collector = new SwarmQueueStatsCollector(
        properties, queueStats, queueMetrics);

    Map<String, QueueStats> snapshot = collector.snapshot(
        new LinkedHashSet<>(java.util.List.of("input", "output")));

    assertThat(snapshot).containsExactlyInAnyOrderEntriesOf(Map.of(
        "ph.swarm-1.input", inputStats,
        "ph.swarm-1.output", outputStats));
    verify(queueMetrics).update("ph.swarm-1.input", inputStats);
    verify(queueMetrics).update("ph.swarm-1.output", outputStats);
  }
}
