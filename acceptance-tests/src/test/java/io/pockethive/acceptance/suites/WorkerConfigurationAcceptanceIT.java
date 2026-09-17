package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.work.api.WorkItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify explicitly authored settings and runtime metadata for every fixture worker.
 * Must not: resolve defaults, templates or resource names, consume CP or implement cleanup.
 * Contract: RESP-ACCEPTANCE-WORKERS — docs/architecture/acceptance-tests.md#resp-acceptance-workers; WK-4/WK-5.
 */
class WorkerConfigurationAcceptanceIT {
  @Test @Tag("worker-config")
  void reportsExplicitBaselineConfigurationForEveryWorker() throws Exception {
    verifyConfiguration("worker-config", "http://wiremock:8080", 2.0);
  }

  @Test @Tag("worker-overrides")
  void reportsExplicitOverridesIncludingGeneratorIoForEveryWorker() throws Exception {
    verifyConfiguration("worker-overrides", "http://wiremock:8080/api", 7.0);
  }

  private static void verifyConfiguration(String name, String expectedBaseUrl, double expectedGeneratorRate)
      throws Exception {
    try (var run = LiveRun.open(name); var swarm = run.newSwarm()) {
      var authored = authoredWorkers(run.scenario);
      assertEquals(expectedGeneratorRate,
          authored.get(BeeRoles.GENERATOR).requiredAt("/config/inputs/scheduler/ratePerSec").doubleValue(),
          "Target must select the intended baseline/override fixture");
      run.evidence.record("expected-base-url", expectedBaseUrl);
      swarm.create(run.createRequest());
      List<WorkItem> samples;
      try (var tap = run.newTap()) {
        tap.open(swarm.id(), run.target.fixture().tap());
        swarm.start();
        samples = tap.awaitSamples(run.target.fixture().samples());
      }
      var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm, authored.keySet());
      for (var worker : workers) {
        String role = worker.required("role").textValue();
        var bee = authored.get(role);
        var expected = bee.required("config");
        var actual = worker.required("config");
        for (String field : new String[]{"enabled", "historyPolicy", "inputs", "outputs"}) {
          assertAuthoredValues(expected.required(field), actual.required(field), role + "." + field);
        }
        switch (role) {
          case BeeRoles.GENERATOR -> assertAuthoredValues(expected.required("message"), actual.required("message"),
              role + ".message");
          case BeeRoles.MODERATOR -> assertAuthoredValues(expected.required("mode"), actual.required("mode"),
              role + ".mode");
          case BeeRoles.PROCESSOR -> {
            assertEquals(expectedBaseUrl, actual.required("baseUrl").textValue(), "Resolved processor URL");
            assertEquals(expected.required("mode"), actual.required("mode"));
            assertEquals(expected.required("threadCount"), actual.required("threadCount"));
          }
          case BeeRoles.POSTPROCESSOR -> {
            for (String field : new String[]{"forwardToOutput", "txOutcomeSinkMode", "dropTxOutcomeWithoutCallId"}) {
              assertEquals(expected.required(field), actual.required(field), role + "." + field);
            }
          }
          default -> fail("Unexpected fixture role: " + role);
        }
        var metadata = worker.required("runtime");
        assertEquals(run.target.fixture().templateId(), metadata.required("templateId").textValue());
        assertEquals(swarm.runId(), metadata.required("runId").textValue());
        assertEquals(bee.required("image"), metadata.required("image"));
        for (String field : new String[]{"containerId", "stackName"}) {
          assertTrue(metadata.required(field).isTextual() && !metadata.required(field).textValue().isBlank(), field);
        }
      }
      String processor = workers.stream().filter(w -> BeeRoles.PROCESSOR.equals(w.required("role").textValue()))
          .findFirst().orElseThrow().required("instance").textValue();
      for (var sample : samples) {
        HttpWorkAssertions.requireSuccessfulResponse(sample, swarm.id(), processor,
            run.target.fixture().expectedResponse());
      }
      swarm.stop();
      swarm.remove();
    }
  }

  private static Map<String, JsonNode> authoredWorkers(JsonNode scenario) {
    var workers = new LinkedHashMap<String, JsonNode>();
    var bees = scenario.requiredAt("/template/bees");
    assertTrue(bees.isArray());
    for (var bee : bees) {
      assertNull(workers.put(bee.required("role").textValue(), bee), "Fixture requires one worker per role");
    }
    assertEquals(Set.of(BeeRoles.GENERATOR, BeeRoles.MODERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR),
        workers.keySet());
    return workers;
  }

  // Compare only authored values: runtime-only defaults and resolved addresses have their product owners.
  private static void assertAuthoredValues(JsonNode expected, JsonNode actual, String path) {
    if (expected.isObject()) {
      assertTrue(actual.isObject(), path);
      if (expected.isEmpty()) assertTrue(actual.isEmpty(), path);
      expected.fields().forEachRemaining(field -> assertAuthoredValues(field.getValue(), actual.required(field.getKey()),
          path + "." + field.getKey()));
    } else if (expected.isNumber()) {
      assertTrue(actual.isNumber(), path);
      assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), path);
    } else {
      assertEquals(expected, actual, path);
    }
  }
}
