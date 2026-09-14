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
import io.pockethive.topology.work.WorkPlaneResources;
import io.pockethive.topology.work.ResolvedWorkTopology;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitResources;
import org.springframework.amqp.core.TopicExchange;

class SwarmRuntimeInfrastructureTest {

  private static final String SWARM_ID = "swarm-1";

  private RabbitResources amqp;
  private SwarmControllerProperties properties;
  private WorkPlaneResources resources;
  private ResolvedWorkTopology topology;
  private ComputeAdapter computeAdapter;
  private SwarmQueueMetrics queueMetrics;
  private SwarmRuntimeInfrastructure infrastructure;

  @BeforeEach
  void setUp() {
    amqp = mock(RabbitResources.class);
    properties = mock(SwarmControllerProperties.class);
    resources = mock(WorkPlaneResources.class);
    topology = new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(new io.pockethive.rabbit.api.RabbitResourceNames(),
        swarm -> new io.pockethive.rabbit.api.RabbitWorkTopologySettings("selected", "selected.hive"))
        .resolve(SWARM_ID, Set.of("generated"));
    var rabbit = new io.pockethive.rabbit.work.RabbitWorkResources(amqp,
        new io.pockethive.rabbit.api.RabbitConnectionSettings("work", 5672, "guest", "guest", "/"));
    when(resources.removalTarget(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> rabbit.removalTarget(call.getArgument(0)));
    computeAdapter = mock(ComputeAdapter.class);
    queueMetrics = mock(SwarmQueueMetrics.class);
    when(properties.getSwarmId()).thenReturn(SWARM_ID);
    when(properties.getTraffic()).thenReturn(new SwarmControllerProperties.Traffic("configured.hive", "configured-prefix"));
    infrastructure = new SwarmRuntimeInfrastructure(
        amqp, properties, resources, computeAdapter, queueMetrics);
  }

  @Test
  void declaresWorkTopologyAndProvisionsExactlyTheSuppliedWorkers() {
    String exchange = "selected.hive";
    Set<String> suffixes = Set.of("generated");
    WorkerSpec worker = new WorkerSpec(
        "generator-1", "generator", "generator:latest", Map.of(), List.of());

    infrastructure.declareWorkTopology(topology);
    infrastructure.provisionWorkers(List.of(worker));

    verify(resources).ensure(topology);
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
        new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "generator-1", io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE),
        new RemoveResource(
            RemoveResourceType.RABBIT_QUEUE,
            "ph.control.swarm-1.generator.generator-1", io.pockethive.swarm.model.lifecycle.ResourcePlane.CONTROL));
  }

  @Test
  void removesQueueMetricsBeforeAFollowingExchangeDeletionFails() {
    var exchange = topology.resources().getFirst();
    org.mockito.Mockito.doThrow(new IllegalStateException("exchange delete failed")).when(resources).remove(exchange);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> infrastructure.removeWorkTopology(topology))
        .hasMessage("exchange delete failed");
    verify(queueMetrics).unregister(topology.channel("generated").inputAddress());
  }

  @Test
  void removesDeclaredWorkTopologyUnregistersMetricsAndClearsItsInventory() {
    String exchange = "selected.hive";
    when(resources.appliedResources()).thenReturn(Set.of(topology.channel("generated").resource()));
    infrastructure.declareWorkTopology(topology);
    assertThat(infrastructure.declaredWorkTopology(topology).channels()).containsOnlyKeys("generated");

    List<RemoveResource> removed = infrastructure.removeWorkTopology(topology);

    verify(queueMetrics).unregister("selected.generated");
    topology.resources().forEach(resource -> verify(resources).remove(resource));
    assertThat(removed).containsExactly(
        new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "selected.generated", io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK),
        new RemoveResource(RemoveResourceType.RABBIT_EXCHANGE, "selected.hive", io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK));
    assertThat(infrastructure.declaredWorkTopology(topology).channels()).isEmpty();
  }
}
