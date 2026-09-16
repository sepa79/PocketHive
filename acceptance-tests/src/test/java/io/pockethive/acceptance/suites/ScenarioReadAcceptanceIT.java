package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.ScenarioApi;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.work.api.HistoryPolicy;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Reads the dedicated authoring fixture; requires no swarm, SUT or running WORK broker. */
@Tag("scenarios")
class ScenarioReadAcceptanceIT {
  @Test void preservesAuthoredSchedulerRate() throws Exception {
    var generator = bee(readFixture("scenario-rate"), BeeRoles.GENERATOR);
    var rate = generator.requiredAt("/config/inputs/scheduler/ratePerSec");
    assertTrue(rate.isNumber(), "Authored rate must remain numeric");
    assertEquals(7.5, rate.doubleValue());
  }

  @Test void preservesTemplatingConfiguration() throws Exception {
    var generator = bee(readFixture("scenario-templating"), BeeRoles.GENERATOR);
    var templating = generator.requiredAt("/config/interceptors/templating");
    assertTrue(templating.isObject());
    assertEquals(1, templating.size(), "Fixture declares exactly one templating setting");
    assertEquals("authoring-probe-{{ headers['x-ph-seq'] }}", templating.required("template").textValue());
  }

  @Test void preservesEveryWorkersHistoryPolicy() throws Exception {
    var workers = readFixture("scenario-history").requiredAt("/template/bees");
    assertTrue(workers.isArray());
    var actual = StreamSupport.stream(workers.spliterator(), false).collect(Collectors.toMap(
        worker -> worker.required("role").textValue(),
        worker -> worker.requiredAt("/config/historyPolicy").textValue()));
    assertEquals(Map.of(
        BeeRoles.GENERATOR, HistoryPolicy.FULL.name(),
        BeeRoles.MODERATOR, HistoryPolicy.LATEST_ONLY.name(),
        BeeRoles.PROCESSOR, HistoryPolicy.DISABLED.name(),
        BeeRoles.POSTPROCESSOR, HistoryPolicy.FULL.name()), actual);
  }

  private static JsonNode readFixture(String testName) throws Exception {
    var target = TargetLoader.loadScenario(TargetLoader.selectedFile());
    try (var api = ApiRun.open(target.api(), testName)) {
      var scenario = new ScenarioApi(api.http, api.token).requireScenario(target.scenarioId());
      api.evidence.record("scenario", scenario);
      assertEquals(target.scenarioId(), scenario.required("id").textValue());
      return scenario;
    }
  }

  private static JsonNode bee(JsonNode scenario, String role) {
    var workers = scenario.requiredAt("/template/bees");
    assertTrue(workers.isArray());
    var matches = StreamSupport.stream(workers.spliterator(), false)
        .filter(worker -> role.equals(worker.required("role").textValue())).toList();
    assertEquals(1, matches.size(), "Fixture must contain exactly one " + role);
    return matches.getFirst();
  }
}
