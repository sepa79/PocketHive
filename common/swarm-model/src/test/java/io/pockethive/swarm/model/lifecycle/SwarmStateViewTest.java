package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SwarmStateViewTest {

  @Test
  void absentRuntimeIntentRequiresStoppedWorkloadIntent() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new SwarmStateView(
        "alpha",
        "run-1",
        RuntimeIntent.ABSENT,
        WorkloadIntent.RUNNING,
        ControllerState.UNKNOWN,
        WorkloadState.UNKNOWN,
        Health.UNKNOWN,
        RuntimeResourceState.UNKNOWN,
        null,
        true,
        null,
        null,
        null,
        List.of(),
        null));
    assertTrue(error.getMessage().contains("ABSENT"));
    assertTrue(error.getMessage().contains("STOPPED"));
  }
}
