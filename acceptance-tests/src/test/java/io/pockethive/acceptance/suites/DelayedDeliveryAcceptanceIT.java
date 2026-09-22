package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.observability.Hop;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies delayed arrival using worker timestamps, independent of tap polling latency. */
@Tag("delayed-delivery")
class DelayedDeliveryAcceptanceIT {
  // Measurement allowance accepted for cross-worker wall-clock timestamps; not broker policy.
  private static final long TIMESTAMP_TOLERANCE_MS = 1;

  @Test void delaysGeneratorResultsBeforeHttpProcessingAndRemovesTheSwarm() throws Exception {
    try (var run = LiveRun.open("delayed-delivery"); var swarm = run.newSwarm()) {
      var bees = run.scenario.requiredAt("/template/bees");
      var generators = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
      bees.forEach(bee -> { if (BeeRoles.GENERATOR.equals(bee.required("role").textValue())) generators.add(bee); });
      assertEquals(1, generators.size());
      var policy = generators.getFirst().requiredAt("/config/outputs/delivery");
      assertEquals("DELAYED", policy.required("mode").textValue());
      long delayMs = policy.required("delayMs").longValue();
      assertTrue(delayMs > 0, "Fixture must exercise a real delay");
      swarm.create(run.createRequest());
      var observations = new ArrayList<Map<String, Object>>();
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture().tap());
        swarm.start();
        String processor = HttpWorkAssertions.processorInstance(run.swarms.state(swarm.id()));
        var samples = tap.awaitSamples(run.target.fixture().samples());
        for (var item : samples) {
          HttpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), processor, run.target.fixture().expectedResponse());
          var hops = item.observabilityContext().orElseThrow().getHops();
          // Scheduler creates the initial generator hop; the interceptor appends execution.
          var generatorHops = hops.stream().filter(hop -> BeeRoles.GENERATOR.equals(hop.getService())).toList();
          assertFalse(generatorHops.isEmpty());
          Hop generated = generatorHops.getLast();
          Hop received = onlyHop(hops, BeeRoles.PROCESSOR);
          assertNotNull(generated.getProcessedAt());
          assertNotNull(received.getReceivedAt());
          long elapsedMs = received.getReceivedAt().toEpochMilli() - generated.getProcessedAt().toEpochMilli();
          var observation = Map.<String, Object>of("messageId", item.messageId(), "configuredDelayMs", delayMs,
              "generatorProcessedAt", generated.getProcessedAt().toString(),
              "processorReceivedAt", received.getReceivedAt().toString(), "elapsedMs", elapsedMs,
              "timestampToleranceMs", TIMESTAMP_TOLERANCE_MS);
          observations.add(observation);
          run.evidence.record("delayed-arrivals", observations);
          assertTrue(elapsedMs >= delayMs - TIMESTAMP_TOLERANCE_MS,
              "Processor received " + item.messageId() + " after only " + elapsedMs
                  + " ms; expected " + delayMs + " ms with " + TIMESTAMP_TOLERANCE_MS + " ms timestamp tolerance");
        }
      }
      swarm.stop();
      swarm.remove();
      assertEquals(swarm.runId(), swarm.removal().runtime().runId());
    }
  }

  private static Hop onlyHop(List<Hop> hops, String role) {
    var matches = hops.stream().filter(hop -> role.equals(hop.getService())).toList();
    assertEquals(1, matches.size(), "Expected exactly one " + role + " hop");
    return matches.getFirst();
  }
}
