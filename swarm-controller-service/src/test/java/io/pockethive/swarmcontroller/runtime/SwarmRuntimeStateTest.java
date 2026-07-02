package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.SwarmPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwarmRuntimeStateTest {

  @Test
  void rejectsDuplicateRuntimeRole() {
    SwarmRuntimeState state = newState();

    state.registerWorker("generator", "gen-a", "container-a");

    assertThatThrownBy(() -> state.registerWorker("generator", "gen-b", "container-b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate runtime worker role: generator");

    assertThat(state.workersByInstance())
        .containsOnlyKeys("gen-a");
    assertThat(state.instancesByRole())
        .containsEntry("generator", List.of("gen-a"));
  }

  @Test
  void rejectsDuplicateRuntimeInstance() {
    SwarmRuntimeState state = newState();

    state.registerWorker("generator", "gen-a", "container-a");

    assertThatThrownBy(() -> state.registerWorker("processor", "gen-a", "container-b"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate runtime worker instance: gen-a");

    assertThat(state.workersByInstance())
        .containsOnlyKeys("gen-a");
    assertThat(state.instancesByRole())
        .containsEntry("generator", List.of("gen-a"))
        .doesNotContainKey("processor");
  }

  @Test
  void rejectsWorkerRegistrationWithoutRuntimeInstance() {
    SwarmRuntimeState state = newState();

    assertThatThrownBy(() -> state.registerWorker("generator", " ", "container-a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role, instanceId, and containerId");

    assertThat(state.instancesByRole()).isEmpty();
    assertThat(state.workersByInstance()).isEmpty();
  }

  private static SwarmRuntimeState newState() {
    return new SwarmRuntimeState(
        new SwarmRuntimeContext(new SwarmPlan("swarm", List.of()), List.of(), Set.of()));
  }
}
