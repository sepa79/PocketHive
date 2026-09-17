package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Responsibility: await fresh, complete worker configuration observations for the owned run.
 * Must not: merge CP messages, resolve configuration or settle lifecycle operations.
 * Contract: RESP-ACCEPTANCE-WORKERS — docs/architecture/acceptance-tests.md#resp-acceptance-workers.
 */
final class WorkerObservations {
  private WorkerObservations() { }
  static List<JsonNode> awaitConfiguredWorkers(LiveRun run, SwarmResource swarm, Set<String> expectedRoles)
      throws Exception {
    var deadline = new Deadline(run.target.limits().operation(), "Current worker configuration for " + swarm.id());
    var json = new ObjectMapper();
    while (true) {
      var state = run.swarms.state(swarm.id(), deadline.remaining());
      run.evidence.record("worker-state", state);
      assertEquals(swarm.id(), state.id());
      assertEquals(swarm.runId(), state.runId());
      assertEquals(WorkloadState.RUNNING, state.workloadState());
      JsonNode workers = json.valueToTree(state.observation()).path("workers");
      if (!state.observationStale() && state.observedAt() != null && workers.isArray()
          && workers.size() == expectedRoles.size()) {
        var instances = new HashSet<String>();
        var roles = new HashSet<String>();
        boolean complete = true;
        for (var worker : workers) {
          String instance = worker.required("instance").textValue();
          assertNotNull(instance);
          assertFalse(instance.isBlank());
          assertTrue(state.bees().stream().anyMatch(bee -> instance.equals(bee.instance())
              && worker.required("role").textValue().equals(bee.role())), "Worker absent from current swarm: " + instance);
          assertTrue(instances.add(instance), "Duplicate runtime instance " + instance);
          assertTrue(roles.add(worker.required("role").textValue()), "Fixture requires one runtime worker per role");
          JsonNode runtimeRun = worker.path("runtime").path("runId");
          if (runtimeRun.isTextual()) assertEquals(swarm.runId(), runtimeRun.textValue(), "Foreign worker run");
          complete &= runtimeRun.isTextual() && worker.path("stale").isBoolean() && !worker.path("stale").booleanValue()
              && worker.path("config").isObject() && !worker.path("config").isEmpty();
        }
        assertEquals(expectedRoles, roles);
        if (complete) return java.util.stream.StreamSupport.stream(workers.spliterator(), false).toList();
      }
      deadline.pause(run.target.limits().poll());
    }
  }
}
