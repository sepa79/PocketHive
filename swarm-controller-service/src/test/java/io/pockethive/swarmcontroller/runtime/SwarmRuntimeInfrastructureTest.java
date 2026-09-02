package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;

class SwarmRuntimeInfrastructureTest {

  private static final String SWARM_ID = "swarm-1";

  private AmqpAdmin amqp;
  private SwarmControllerProperties properties;
  private SwarmWorkTopologyManager topology;
  private ComputeAdapter computeAdapter;
  private SwarmQueueMetrics queueMetrics;
  private SwarmRuntimeInfrastructure infrastructure;

  @BeforeEach
  void setUp() {
    amqp = mock(AmqpAdmin.class);
    properties = mock(SwarmControllerProperties.class);
    topology = mock(SwarmWorkTopologyManager.class);
    computeAdapter = mock(ComputeAdapter.class);
    queueMetrics = mock(SwarmQueueMetrics.class);
    when(properties.getSwarmId()).thenReturn(SWARM_ID);
    infrastructure = new SwarmRuntimeInfrastructure(
        amqp, properties, topology, computeAdapter, queueMetrics);
  }

  @Test
  void declaresWorkTopologyAndProvisionsExactlyTheSuppliedWorkers() {
    TopicExchange exchange = new TopicExchange("ph.swarm-1.hive");
    Set<String> suffixes = Set.of("generated");
    WorkerSpec worker = new WorkerSpec(
        "generator-1", "generator", "generator:latest", Map.of(), List.of());
    when(topology.declareWorkExchange()).thenReturn(exchange);

    infrastructure.declareWorkTopology(suffixes);
    infrastructure.provisionWorkers(List.of(worker));

    verify(topology).declareWorkQueues(eq(exchange), eq(suffixes), anySet());
    verify(computeAdapter).applyWorkers(SWARM_ID, List.of(worker));
  }

  @Test
  void removesWorkersAndControlQueuesAndReportsEveryTarget() {
    when(properties.controlQueueName("generator", "generator-1"))
        .thenReturn("ph.control.swarm-1.generator.generator-1");

    List<RemoveResource> removed = infrastructure.removeWorkers(
        Map.of("generator", List.of("generator-1")));

    verify(computeAdapter).removeWorkers(SWARM_ID);
    verify(amqp).deleteQueue("ph.control.swarm-1.generator.generator-1");
    assertThat(removed).containsExactly(
        new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "generator-1"),
        new RemoveResource(
            RemoveResourceType.RABBIT_QUEUE,
            "ph.control.swarm-1.generator.generator-1"));
  }

  @Test
  void removesDeclaredWorkTopologyUnregistersMetricsAndClearsItsInventory() {
    TopicExchange exchange = new TopicExchange("ph.swarm-1.hive");
    when(topology.declareWorkExchange()).thenReturn(exchange);
    when(properties.queueName("generated")).thenReturn("ph.swarm-1.generated");
    when(properties.hiveExchange()).thenReturn("ph.swarm-1.hive");
    doAnswer(invocation -> {
      Set<String> suffixes = invocation.getArgument(1);
      Set<String> declared = invocation.getArgument(2);
      declared.addAll(suffixes);
      return null;
    }).when(topology).declareWorkQueues(eq(exchange), eq(Set.of("generated")), anySet());
    doAnswer(invocation -> {
      Consumer<String> onQueueDeleted = invocation.getArgument(1);
      onQueueDeleted.accept("ph.swarm-1.generated");
      return null;
    }).when(topology).deleteWorkQueues(eq(Set.of("generated")), org.mockito.ArgumentMatchers.any());

    infrastructure.declareWorkTopology(Set.of("generated"));
    assertThat(infrastructure.declaredQueueSuffixes()).containsExactly("generated");

    List<RemoveResource> removed = infrastructure.removeWorkTopology(
        infrastructure.declaredQueueSuffixes());

    verify(queueMetrics).unregister("ph.swarm-1.generated");
    verify(topology).deleteWorkExchange();
    assertThat(removed).containsExactly(
        new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "ph.swarm-1.generated"),
        new RemoveResource(RemoveResourceType.RABBIT_EXCHANGE, "ph.swarm-1.hive"));
    assertThat(infrastructure.declaredQueueSuffixes()).isEmpty();
  }
}
