package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify a scenario's scheduled worker changes and final stop through public observations.
 * Must not: schedule actions, merge CP state, send corrective commands or implement resource cleanup.
 * Contract: RESP-ACCEPTANCE-WORKERS — docs/architecture/acceptance-tests.md#scenario-timeline-acceptance-sw-3.
 */
@Tag("scenario-plan")
class ScenarioPlanAcceptanceIT {
  private static final Set<String> ROLES = Set.of(BeeRoles.GENERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR);
  private static final List<String> STEPS = List.of("enable-work", "rate-seven", "pause-generator", "resume-generator", "stop-work");

  @Test void planChangesGeneratorAndStopsWorkWithoutManualCommands() throws Exception {
    try (var run = LiveRun.open("scenario-plan"); var swarm = run.newSwarm()) {
      // Read-only fixture check: expected examples, not a second plan parser/scheduler.
      assertEquals("rate-seven", run.scenario.requiredAt("/plan/bees/0/steps/0/stepId").textValue());
      assertEquals(7.0, run.scenario.requiredAt("/plan/bees/0/steps/0/config/inputs/scheduler/ratePerSec").doubleValue());
      swarm.create(run.createRequest());
      var started = swarm.start();
      assertEquals(BeeRoles.SWARM_CONTROLLER, started.target().role());
      awaitPhase(run, swarm, "plan-baseline", WorkloadState.RUNNING, 2.0, true, true);
      var fast = awaitPhase(run, swarm, "plan-rate-seven", WorkloadState.RUNNING, 7.0, true, true);
      captureResponses(run, swarm, fast);
      awaitPhase(run, swarm, "plan-paused", WorkloadState.RUNNING, 7.0, false, true);
      var resumed = awaitPhase(run, swarm, "plan-resumed", WorkloadState.RUNNING, 7.0, true, true);
      captureResponses(run, swarm, resumed);
      awaitPhase(run, swarm, "plan-stopped", WorkloadState.STOPPED, 7.0, false, false);
      requireCompletedJournal(run, swarm, started.target().instance());
      swarm.remove();
    }
  }

  private static List<JsonNode> awaitPhase(LiveRun run, SwarmResource swarm, String phase,
      WorkloadState expectedState, double rate, boolean generatorEnabled, boolean otherWorkersEnabled) throws Exception {
    return WorkerObservations.awaitConfiguredWorkers(run, swarm, ROLES, phase, (state, workers) -> {
      if (state.workloadState() != expectedState || state.activeOperation() != null) return false;
      for (var worker : workers) {
        boolean generator = BeeRoles.GENERATOR.equals(worker.required("role").textValue());
        var enabled = worker.required("enabled");
        assertTrue(enabled.isBoolean());
        if (enabled.booleanValue() != (generator ? generatorEnabled : otherWorkersEnabled)) return false;
        if (generator) {
          var actualRate = worker.requiredAt("/config/inputs/scheduler/ratePerSec");
          assertTrue(actualRate.isNumber());
          if (actualRate.doubleValue() != rate) return false;
        }
      }
      return true;
    });
  }

  private static void captureResponses(LiveRun run, SwarmResource swarm, List<JsonNode> workers) throws Exception {
    String processor = workers.stream().filter(w -> BeeRoles.PROCESSOR.equals(w.required("role").textValue()))
        .findFirst().orElseThrow().required("instance").textValue();
    try (var tap = run.newTap()) {
      tap.open(swarm.id(), run.target.fixture().tap());
      for (var sample : tap.awaitSamples(run.target.fixture().samples())) {
        HttpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(), processor, run.target.fixture().expectedResponse());
      }
    }
  }

  private static void requireCompletedJournal(LiveRun run, SwarmResource swarm, String controller) throws Exception {
    var deadline = new Deadline(run.target.limits().operation(), "Completed scenario journal for " + swarm.id());
    while (true) {
      var entries = run.journal.read(swarm.id(), swarm.runId(), deadline.remaining());
      run.evidence.record("plan-journal", entries);
      assertTrue(entries.isArray());
      var completedSteps = new ArrayList<String>();
      int completedPlans = 0;
      for (var entry : entries) {
        if (!"plan".equals(entry.path("kind").textValue())) continue;
        assertEquals(swarm.id(), entry.required("swarmId").textValue());
        assertEquals(swarm.runId(), entry.required("runId").textValue());
        assertEquals(swarm.id(), entry.requiredAt("/scope/swarmId").textValue());
        assertEquals(BeeRoles.SWARM_CONTROLLER, entry.requiredAt("/scope/role").textValue());
        assertEquals(controller, entry.requiredAt("/scope/instance").textValue());
        assertNotEquals("ERROR", entry.required("severity").textValue(), entry.toString());
        String type = entry.required("type").textValue();
        if ("scenario-step-completed".equals(type)) completedSteps.add(entry.requiredAt("/data/stepId").textValue());
        if ("scenario-plan-completed".equals(type)) completedPlans++;
      }
      if (completedPlans > 0) {
        assertEquals(1, completedPlans);
        assertEquals(STEPS, completedSteps);
        return;
      }
      deadline.pause(run.target.limits().poll());
    }
  }
}
