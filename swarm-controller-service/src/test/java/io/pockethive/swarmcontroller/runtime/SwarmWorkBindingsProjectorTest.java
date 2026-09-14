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

  private final SwarmWorkBindingsProjector projector = new SwarmWorkBindingsProjector();
  private final io.pockethive.topology.work.ResolvedWorkTopology topology = topology();

  private static io.pockethive.topology.work.ResolvedWorkTopology topology() {
    var names = org.mockito.Mockito.spy(new io.pockethive.rabbit.api.RabbitResourceNames());
    org.mockito.Mockito.doAnswer(call -> new io.pockethive.rabbit.api.RabbitWorkAddress("selected.hive",
        "selected." + call.getArgument(2), "route." + call.getArgument(2)))
        .when(names).address(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    return new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(names,
        swarm -> new io.pockethive.rabbit.api.RabbitWorkTopologySettings("selected", "selected.hive"))
        .resolve("test", java.util.Set.of("generator.fast", "processor.fast", "generator"));
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
        Map.of("generator", List.of("generator-1"), "processor", List.of("processor-1")), topology);

    assertThat(projection).isEqualTo(Map.of(
        "exchange", "selected.hive",
        "edges", List.of(Map.of(
            "edgeId", "edge-fast",
            "from", Map.of(
                "role", "generator",
                "instance", "generator-1",
                "port", "out.fast",
                "routingKey", "route.generator.fast"),
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
    assertThat(projector.project(null, Map.of(), topology)).isEqualTo(Map.of(
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
        Map.of("generator", List.of("generator-1", "generator-2")), topology))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate runtime worker role");
  }

  private static Bee bee(String role, Map<String, String> input, Map<String, String> output) {
    return new Bee(role, role + ":latest", new Work(input, output), Map.of());
  }
}
