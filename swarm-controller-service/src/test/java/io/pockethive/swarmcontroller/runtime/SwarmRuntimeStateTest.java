package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.SwarmPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SwarmRuntimeStateTest {

  @Test
  void recordsCanonicalRuntimeBeeIdTargetsWithoutCollapsingDuplicateRoles() {
    SwarmRuntimeState state = new SwarmRuntimeState(
        new SwarmRuntimeContext(new SwarmPlan("swarm", List.of()), List.of(), Set.of()));

    state.registerWorker("runtime-bee-a", "generator", "gen-a", "container-a");
    state.registerWorker("runtime-bee-b", "generator", "gen-b", "container-b");

    assertThat(state.workerByBeeId("runtime-bee-a"))
        .hasValue(new SwarmRuntimeState.WorkerTarget("generator", "gen-a", "container-a"));
    assertThat(state.workerByBeeId("runtime-bee-b"))
        .hasValue(new SwarmRuntimeState.WorkerTarget("generator", "gen-b", "container-b"));
    assertThat(state.beeIdFor("generator", "gen-a")).hasValue("runtime-bee-a");
    assertThat(state.beeIdFor("generator", "gen-b")).hasValue("runtime-bee-b");
    assertThat(state.instanceByBeeId())
        .containsEntry("runtime-bee-a", "gen-a")
        .containsEntry("runtime-bee-b", "gen-b");
    assertThat(state.instancesByRole())
        .containsEntry("generator", List.of("gen-a", "gen-b"));
  }

  @Test
  void keepsRuntimeBeeIdMappingConsistentWhenATargetIsReregistered() {
    SwarmRuntimeState state = new SwarmRuntimeState(
        new SwarmRuntimeContext(new SwarmPlan("swarm", List.of()), List.of(), Set.of()));

    state.registerWorker("runtime-bee-a", "generator", "gen-a", "container-a");
    state.registerWorker("runtime-bee-b", "generator", "gen-a", "container-b");

    assertThat(state.workerByBeeId("runtime-bee-a")).isEmpty();
    assertThat(state.workerByBeeId("runtime-bee-b"))
        .hasValue(new SwarmRuntimeState.WorkerTarget("generator", "gen-a", "container-b"));
    assertThat(state.beeIdFor("generator", "gen-a")).hasValue("runtime-bee-b");
  }

  @Test
  void rejectsWorkerRegistrationWithoutRuntimeBeeId() {
    SwarmRuntimeState state = new SwarmRuntimeState(
        new SwarmRuntimeContext(new SwarmPlan("swarm", List.of()), List.of(), Set.of()));

    assertThatThrownBy(() -> state.registerWorker(" ", "generator", "gen-a", "container-a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beeId");

    assertThat(state.instancesByRole()).isEmpty();
    assertThat(state.workersByBeeId()).isEmpty();
  }
}
