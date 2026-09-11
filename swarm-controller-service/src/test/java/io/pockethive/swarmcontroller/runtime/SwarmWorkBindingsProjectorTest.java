package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TopologyEdge;
import io.pockethive.swarm.model.TopologyEndpoint;
import io.pockethive.swarm.model.TopologySelector;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmWorkBindingsProjectorTest {

  private final SwarmWorkBindingsProjector projector = new SwarmWorkBindingsProjector(
      new SwarmControllerProperties.Traffic("ph.test.hive", "ph.test"), selectedNames());

  private static io.pockethive.topology.work.WorkResourceNamesPort selectedNames() {
    var names = org.mockito.Mockito.mock(io.pockethive.topology.work.WorkResourceNamesPort.class);
    org.mockito.Mockito.when(names.queueName(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> "selected." + call.getArgument(1));
    org.mockito.Mockito.when(names.exchangeName(org.mockito.ArgumentMatchers.anyString())).thenReturn("selected.hive");
    return names;
  }

  @Test
  void projectsTopologyPortsSelectorsAndMaterializedInstances() {
    Bee generator = bee("generator", Map.of(), Map.of("out.fast", "generator.fast"));
    Bee processor = bee("processor", Map.of("in.fast", "processor.fast"), Map.of());
    TopologyEdge edge = new TopologyEdge(
        "edge-fast",
        new TopologyEndpoint("generator", "out.fast"),
        new TopologyEndpoint("processor", "in.fast"),
        new TopologySelector("predicate", "payload.priority >= 50"));
    SwarmPlan plan = new SwarmPlan(
        "test",
        List.of(generator, processor),
        new Topology(1, List.of(edge)),
        null,
        null,
        null);

    Map<String, Object> projection = projector.project(
        plan,
        Map.of("generator", List.of("generator-1"), "processor", List.of("processor-1")));

    assertThat(projection).isEqualTo(Map.of(
        "exchange", "selected.hive",
        "edges", List.of(Map.of(
            "edgeId", "edge-fast",
            "from", Map.of(
                "role", "generator",
                "instance", "generator-1",
                "port", "out.fast",
                "routingKey", "selected.generator.fast"),
            "to", Map.of(
                "role", "processor",
                "instance", "processor-1",
                "port", "in.fast",
                "queue", "selected.processor.fast"),
            "selector", Map.of(
                "policy", "predicate",
                "expr", "payload.priority >= 50")))));
  }

  @Test
  void projectsEmptyBindingsBeforeAPlanIsPrepared() {
    assertThat(projector.project(null, Map.of())).isEqualTo(Map.of(
        "exchange", "selected.hive",
        "edges", List.of()));
  }

  @Test
  void rejectsAmbiguousRuntimeInstanceMapping() {
    Bee generator = bee("generator", Map.of(), Map.of("out", "generator"));
    TopologyEdge edge = new TopologyEdge(
        "loop",
        new TopologyEndpoint("generator", "out"),
        new TopologyEndpoint("generator", "out"),
        null);
    SwarmPlan plan = new SwarmPlan(
        "test", List.of(generator), new Topology(1, List.of(edge)), null, null, null);

    assertThatThrownBy(() -> projector.project(
        plan,
        Map.of("generator", List.of("generator-1", "generator-2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate runtime worker role");
  }

  private static Bee bee(String role, Map<String, String> input, Map<String, String> output) {
    return new Bee(role, role + ":latest", new Work(input, output), Map.of());
  }
}
