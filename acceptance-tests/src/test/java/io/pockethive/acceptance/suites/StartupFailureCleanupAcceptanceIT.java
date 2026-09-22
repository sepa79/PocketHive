package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;

import io.pockethive.swarm.model.lifecycle.ControllerState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify explicit removal after a selected incompatible fixture fails Controller preparation.
 * Must not: switch deployment adapters, reset the registry or delete infrastructure directly.
 * Contract: docs/ORCHESTRATOR-REST.md Create swarm and canonical filesystem REMOVE.
 */
@Tag("startup-failure")
class StartupFailureCleanupAcceptanceIT {
  @Test
  void failedPreparationRemainsObservableAndRemovable() throws Exception {
    try (var run = LiveRun.open("startup-failure-cleanup")) {
      var swarm = run.newSwarm();
      try (swarm) {
        var createFailure = assertThrows(AssertionError.class, () -> swarm.create(run.createRequest()));
        assertTrue(createFailure.getMessage().contains("ended FAILED"), createFailure::getMessage);
        var state = run.swarms.state(swarm.id());
        run.evidence.record("failed-startup-state", state);
        assertEquals(ControllerState.FAILED, state.controllerState());
        var startFailure = assertThrows(AssertionError.class, swarm::start);
        assertTrue(startFailure.getMessage().contains("ended REJECTED"), startFailure::getMessage);
      }
      assertEquals(swarm.runId(), swarm.removal().runtime().runId());
      run.swarms.requireAbsent(swarm.id());
    }
  }
}
