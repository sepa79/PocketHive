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
    QueueStatsPort queueStats = mock(QueueStatsPort.class);
    SwarmQueueMetrics queueMetrics = mock(SwarmQueueMetrics.class);
    QueueStats inputStats = new QueueStats(5, 2, OptionalLong.of(17));
    QueueStats outputStats = QueueStats.empty();
    when(queueStats.getQueueStats("selected.input")).thenReturn(inputStats);
    when(queueStats.getQueueStats("selected.output")).thenReturn(outputStats);
    SwarmQueueStatsCollector collector = new SwarmQueueStatsCollector(
        queueStats, queueMetrics);

    Map<String, QueueStats> snapshot = collector.snapshot(
        new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(new io.pockethive.rabbit.api.RabbitResourceNames(),
            swarm -> new io.pockethive.rabbit.api.RabbitWorkTopologySettings("selected", "selected.hive"))
            .resolve("swarm-1", new LinkedHashSet<>(java.util.List.of("input", "output"))));

    assertThat(snapshot).containsExactlyInAnyOrderEntriesOf(Map.of(
        "selected.input", inputStats,
        "selected.output", outputStats));
    verify(queueMetrics).update("selected.input", inputStats);
    verify(queueMetrics).update("selected.output", outputStats);
  }
}
