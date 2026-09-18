package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.work.api.WorkItem;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify captured HTTP outcomes are persisted for the same swarm, trace and sink instance.
 * Must not: project sink events, infer persistence from counters or delete telemetry.
 * Contract: docs/architecture/acceptance-tests.md#transaction-outcome-persistence-acceptance-da-3.
 */
@Tag("tx-outcome")
class TxOutcomeAcceptanceIT {
  @Test void persistsCapturedProcessorResultsInClickHouse() throws Exception {
    var target = TargetLoader.loadTxOutcome(TargetLoader.selectedFile());
    try (var run = LiveRun.open("tx-outcome", target.lifecycle()); var swarm = run.newSwarm()) {
      var outcomes = run.outcomes(target);
      var before = outcomes.read(swarm.id(), run.target.limits().request());
      run.evidence.record("outcomes-before", before);
      assertTrue(before.isEmpty(), "Fresh swarm must not have stored outcomes");
      swarm.create(run.createRequest());
      List<WorkItem> samples;
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture().tap());
        swarm.start();
        samples = tap.awaitSamples(run.target.fixture().samples());
      }
      var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
          Set.of(BeeRoles.GENERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR));
      var instances = workers.stream().collect(Collectors.toMap(
          worker -> worker.required("role").textValue(), worker -> worker.required("instance").textValue()));
      var sink = workers.stream().filter(worker -> BeeRoles.POSTPROCESSOR.equals(worker.required("role").textValue()))
          .findFirst().orElseThrow();
      var authored = java.util.stream.StreamSupport.stream(run.scenario.required("template").required("bees").spliterator(), false)
          .filter(bee -> BeeRoles.POSTPROCESSOR.equals(bee.required("role").textValue())).findFirst().orElseThrow();
      assertEquals(authored.required("config").required("txOutcomeSinkMode"), sink.required("config").required("txOutcomeSinkMode"));
      var traces = samples.stream().map(item -> item.observabilityContext().orElseThrow().getTraceId()).toList();
      assertTrue(traces.stream().allMatch(trace -> trace != null && !trace.isBlank()));
      assertEquals(samples.size(), Set.copyOf(traces).size(), "Distinct input traces");
      for (var item : samples) {
        HttpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), instances.get(BeeRoles.PROCESSOR),
            run.target.fixture().expectedResponse());
        assertEquals("true", item.stepHeaders().get(OutcomeHeaders.PROCESSOR_SUCCESS));
        assertFalse(((String) item.headers().get(OutcomeHeaders.CALL_ID)).isBlank());
      }
      var deadline = new Deadline(run.target.limits().capture(), "Persisted outcomes for " + swarm.id());
      while (true) {
        var rows = outcomes.read(swarm.id(), deadline.remaining());
        run.evidence.record("persisted-outcomes", rows);
        for (var row : rows) {
          assertEquals(swarm.id(), row.required("swarmId").textValue());
          assertEquals(BeeRoles.POSTPROCESSOR, row.required("sinkRole").textValue());
          assertEquals(instances.get(BeeRoles.POSTPROCESSOR), row.required("sinkInstance").textValue());
        }
        boolean complete = true;
        for (var item : samples) {
          String trace = item.observabilityContext().orElseThrow().getTraceId();
          List<JsonNode> matches = rows.stream().filter(row -> trace.equals(row.required("traceId").textValue())).toList();
          if (matches.isEmpty()) { complete = false; continue; }
          assertEquals(1, matches.size(), "One stored event for captured trace " + trace);
          var row = matches.getFirst();
          var result = item.asJson(io.pockethive.work.api.HttpResultEnvelope.class);
          assertEquals(item.headers().get(OutcomeHeaders.CALL_ID), row.required("callId").textValue());
          assertTrue(row.required("processorStatus").isIntegralNumber());
          assertEquals(result.outcome().status(), row.required("processorStatus").intValue());
          assertTrue(row.required("processorSuccess").isIntegralNumber());
          assertEquals(1, row.required("processorSuccess").intValue());
          assertTrue(row.required("processorDurationMs").isIntegralNumber());
          assertEquals(result.metrics().durationMs(), row.required("processorDurationMs").longValue());
        }
        if (complete) break;
        deadline.pause(run.target.limits().poll());
      }
      swarm.stop();
      swarm.remove();
    }
  }
}
