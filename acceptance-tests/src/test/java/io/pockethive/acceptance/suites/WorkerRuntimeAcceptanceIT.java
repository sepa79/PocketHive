package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.work.api.HistoryPolicy;
import io.pockethive.work.api.HttpResultEnvelope;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify authored history policies at runtime and processor step-header separation.
 * Must not: resolve effective worker configuration, consume CP directly or implement resource cleanup.
 * Contract: docs/architecture/acceptance-tests.md#worker-runtime-acceptance-slice — WK-1/WK-2.
 */
@Tag("workers")
class WorkerRuntimeAcceptanceIT {
  @Test void honorsAuthoredHistoryPolicyInCapturedHttpResults() throws Exception {
    try (var run = LiveRun.open("worker-history"); var swarm = run.newSwarm()) {
      var policies = authoredPolicies(run.scenario);
      assertEquals(HistoryPolicy.LATEST_ONLY.name(), policies.get(BeeRoles.PROCESSOR),
          "This fixture expects the processor to retain only its result step");
      swarm.create(run.createRequest());
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture());
        swarm.start();
        var samples = tap.awaitSamples(run.target.fixture().samples());
        var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm, policies.keySet());
        for (var worker : workers) {
          String role = worker.required("role").textValue();
          assertEquals(policies.get(role), worker.requiredAt("/config/historyPolicy").textValue(),
              "Runtime history policy for " + worker.required("instance").textValue());
        }
        var processors = workers.stream().filter(worker -> BeeRoles.PROCESSOR.equals(worker.required("role").textValue())).toList();
        assertEquals(1, processors.size());
        String processor = processors.getFirst().required("instance").textValue();
        for (var sample : samples) {
          HttpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(), processor, run.target.fixture().expectedResponse());
          var steps = java.util.stream.StreamSupport.stream(sample.steps().spliterator(), false).toList();
          assertEquals(1, steps.size(), "LATEST_ONLY must remove earlier steps from the actual result");
          assertEquals(0, steps.getFirst().index(), "The retained result is reindexed to zero");
        }
      }
      swarm.stop();
      swarm.remove();
    }
  }

  @Test void keepsProcessorResultHeadersInItsStepOnly() throws Exception {
    try (var run = LiveRun.open("worker-headers"); var swarm = run.newSwarm()) {
      swarm.create(run.createRequest());
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture());
        swarm.start();
        var state = run.swarms.state(swarm.id());
        assertEquals(swarm.id(), state.id());
        assertEquals(swarm.runId(), state.runId());
        assertEquals(WorkloadState.RUNNING, state.workloadState());
        String processor = HttpWorkAssertions.processorInstance(state);
        for (var item : tap.awaitSamples(run.target.fixture().samples())) {
          assertAll("HTTP result and step headers",
              () -> HttpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), processor, run.target.fixture().expectedResponse()),
              () -> {
                var result = item.asJson(HttpResultEnvelope.class);
                var stepHeaders = item.stepHeaders();
                assertEquals("200", stepHeaders.get(OutcomeHeaders.PROCESSOR_STATUS));
                assertEquals("true", stepHeaders.get(OutcomeHeaders.PROCESSOR_SUCCESS));
                assertEquals(Long.toString(result.metrics().durationMs()), stepHeaders.get(OutcomeHeaders.PROCESSOR_DURATION_MS));
                for (String header : stepHeaders.keySet()) {
                  assertFalse(item.headers().containsKey(header), "Processor step header leaked into global headers: " + header);
                }
              });
        }
      }
      swarm.stop();
      swarm.remove();
    }
  }

  private static Map<String, String> authoredPolicies(JsonNode scenario) {
    var bees = scenario.requiredAt("/template/bees");
    assertTrue(bees.isArray(), "Authoring must contain a bee array");
    Map<String, String> policies = new LinkedHashMap<>();
    for (var bee : bees) {
      assertNull(policies.put(bee.required("role").textValue(), bee.requiredAt("/config/historyPolicy").textValue()),
          "This fixture requires one bee per role");
    }
    assertEquals(Set.of(BeeRoles.GENERATOR, BeeRoles.MODERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR), policies.keySet());
    assertEquals(Set.of(HistoryPolicy.FULL.name(), HistoryPolicy.LATEST_ONLY.name()),
        new HashSet<>(policies.values()), "Fixture must exercise every history policy");
    return Map.copyOf(policies);
  }

}
