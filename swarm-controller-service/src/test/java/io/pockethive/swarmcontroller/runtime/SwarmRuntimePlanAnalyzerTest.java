package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Work;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmRuntimePlanAnalyzerTest {

  @Test
  void derivesQueuesAndRunnableBees() {
    Bee generator = bee("generator", "generator:latest", null, "generated");
    Bee processor = bee("processor", "processor:latest", "generated", "processed");
    Bee sink = bee("sink", null, "processed", null);
    SwarmPlan plan = new SwarmPlan("swarm", List.of(processor, sink, generator));

    SwarmRuntimeContext context = SwarmRuntimePlanAnalyzer.analyze(plan);

    assertThat(context.queueSuffixes()).containsExactlyInAnyOrder("generated", "processed");
    assertThat(context.runnableBees()).containsExactly(processor, generator);
  }

  @Test
  void ignoresBlankQueueSuffixes() {
    Bee bee = new Bee(
        "generator",
        "generator:latest",
        new Work(Map.of("ignored", " "), Map.of("output", "generated")),
        Map.of());

    SwarmRuntimeContext context = SwarmRuntimePlanAnalyzer.analyze(
        new SwarmPlan("swarm", List.of(bee)));

    assertThat(context.queueSuffixes()).containsExactly("generated");
  }

  private static Bee bee(String role, String image, String input, String output) {
    return new Bee(role, image, Work.ofDefaults(input, output), Map.of());
  }
}
