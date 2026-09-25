package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests new lifecycle requests at their target state, as specified in ORCHESTRATOR-REST §3.1–3.2. */
@Tag("lifecycle")
@Tag("target-state")
class TargetStateLifecycleAcceptanceIT {
  @Test
  void acceptsStopBeforeStartAndRepeatedRequestsAtTargetState() throws Exception {
    try (var run = LiveRun.open("target-state-lifecycle"); var swarm = run.newSwarm()) {
      swarm.create(run.createRequest());

      var stoppedBeforeStart = swarm.stop();
      assertState(run, swarm, WorkloadState.STOPPED);
      var repeatedStop = swarm.stop();
      assertState(run, swarm, WorkloadState.STOPPED);

      var started = swarm.start();
      assertState(run, swarm, WorkloadState.RUNNING);
      var repeatedStart = swarm.start();
      assertState(run, swarm, WorkloadState.RUNNING);

      // New keys must produce new operations; replaying an earlier success cannot satisfy this test.
      var operations = List.of(stoppedBeforeStart, repeatedStop, started, repeatedStart);
      assertEquals(operations.size(), operations.stream().map(SwarmOperation::idempotencyKey).distinct().count(),
          "Each lifecycle request must have a distinct idempotency key");
      assertEquals(operations.size(), operations.stream().map(SwarmOperation::correlationId).distinct().count(),
          "Each new key must have its own operation, including requests at the target state");

      swarm.stop();
      assertState(run, swarm, WorkloadState.STOPPED);
      swarm.remove();
      assertEquals(swarm.runId(), swarm.removal().runtime().runId());
    }
  }

  private static void assertState(LiveRun run, SwarmResource swarm, WorkloadState expected) throws Exception {
    var state = run.swarms.state(swarm.id());
    assertEquals(swarm.runId(), state.runId());
    assertEquals(ControllerState.READY, state.controllerState());
    assertEquals(expected, state.workloadState());
  }
}
