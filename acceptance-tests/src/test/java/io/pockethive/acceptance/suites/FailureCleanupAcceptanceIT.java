package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lifecycle")
class FailureCleanupAcceptanceIT {
  @Test void assertionFailureAfterCreateStillRemovesTheOwnedSwarm() throws Exception {
    try (var run = LiveRun.open("failure-cleanup")) {
      var swarm = run.newSwarm();
      AssertionError deliberate = new AssertionError("Deliberate test-body failure after successful create");
      AssertionError observed = assertThrows(AssertionError.class, () -> {
        try (swarm) {
          swarm.create(run.createRequest());
          throw deliberate;
        }
      });
      assertSame(deliberate, observed);
      assertEquals(0, observed.getSuppressed().length, "Cleanup failed alongside the expected test-body failure");
      assertEquals(swarm.runId(), swarm.removal().runtime().runId());
      run.swarms.requireAbsent(swarm.id());
    }
  }
}
