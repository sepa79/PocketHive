package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.work.api.HttpResultEnvelope;
import io.pockethive.work.api.WorkItem;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("lifecycle")
class HttpLifecycleAcceptanceIT {
  @Test void processesHttpWorkAndRemovesItsSwarm() throws Exception {
    try (var run = LiveRun.open("http-lifecycle"); var swarm = run.newSwarm()) {
      swarm.create(run.createRequest());
      var created = run.swarms.state(swarm.id());
      assertEquals(swarm.runId(), created.runId());
      assertEquals(ControllerState.READY, created.controllerState());
      assertEquals(WorkloadState.STOPPED, created.workloadState());
      assertEquals(Set.of(BeeRoles.GENERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR),
          created.bees().stream().map(bee -> bee.role()).collect(Collectors.toSet()));
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture());
        swarm.start();
        var running = run.swarms.state(swarm.id());
        assertEquals(swarm.runId(), running.runId());
        assertEquals(WorkloadState.RUNNING, running.workloadState());
        var samples = tap.awaitSamples(run.target.fixture().samples());
        assertEquals(run.target.fixture().samples(), samples.stream().map(WorkItem::messageId).distinct().count());
        ObjectMapper json = new ObjectMapper();
        for (WorkItem item : samples) {
          assertEquals(swarm.id(), item.observabilityContext().orElseThrow().getSwarmId());
          assertEquals(BeeRoles.PROCESSOR, item.stepHeaders().get(WorkItem.STEP_SERVICE_HEADER));
          HttpResultEnvelope result = item.asJson(HttpResultEnvelope.class);
          assertEquals(HttpResultEnvelope.OUTCOME_HTTP_RESPONSE, result.outcome().type());
          assertEquals(200, result.outcome().status());
          assertNull(result.outcome().error());
          assertEquals(json.readTree(run.target.fixture().expectedResponse()), json.readTree(result.outcome().body()));
        }
      }
      swarm.stop();
      var stopped = run.swarms.state(swarm.id());
      assertEquals(swarm.runId(), stopped.runId());
      assertEquals(WorkloadState.STOPPED, stopped.workloadState());
      swarm.remove();
      assertEquals(swarm.runId(), swarm.removal().runtime().runId());
    }
  }
}
