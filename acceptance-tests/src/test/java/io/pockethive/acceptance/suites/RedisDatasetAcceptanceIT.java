package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.resources.RedisListResource;
import io.pockethive.acceptance.resources.RedisDatasetResources;
import io.pockethive.acceptance.resources.ScenarioResource;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.work.api.HttpRequestEnvelope;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkStep;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify isolated Redis records survive generation, request rendering and HTTP processing.
 * Must not: resolve broker addresses, parse production configuration or share mutable dataset fixtures.
 * Contract: docs/architecture/acceptance-tests.md#redis-dataset-acceptance-da-1da-2.
 */
@Tag("redis-data")
class RedisDatasetAcceptanceIT {
  @Test void processesBothDatasetRecordsWithExactRenderedRequests() throws Exception {
    var target = TargetLoader.loadRedisData(TargetLoader.selectedFile());
    assertEquals(2, target.lifecycle().fixture().samples(), "Dataset fixture contains exactly two records");
    var json = new ObjectMapper();
    String scenarioId = "acceptance-dataset-" + UUID.randomUUID();
    String nonce = UUID.randomUUID().toString();
    Set<JsonNode> expected = Set.of(
        json.valueToTree(Map.of("customer", "first", "account", "001", "amount", 17, "nonce", nonce)),
        json.valueToTree(Map.of("customer", "second", "account", "002", "amount", 29, "nonce", nonce)));
    try (var run = LiveRun.open("redis-dataset", target.lifecycle())) {
      var first = new RedisListResource(run.redis(target.connectionId()), run.evidence);
      var second = new RedisListResource(run.redis(target.connectionId()), run.evidence);
      var scenario = new ScenarioResource(scenarioId, run.scenarios, run.evidence);
      var swarm = run.newSwarm();
      try (var dependencies = new RedisDatasetResources(swarm, scenario, java.util.List.of(first, second), run.evidence); swarm) {
        var records = expected.stream().toList();
        ObjectNode owned = run.scenario.deepCopy();
        owned.put("id", scenarioId);
        owned.put("name", scenarioId);
        var generators = StreamSupport.stream(owned.required("template").required("bees").spliterator(), false)
            .filter(bee -> BeeRoles.GENERATOR.equals(bee.required("role").textValue())).toList();
        assertEquals(1, generators.size());
        var sources = generators.getFirst().required("config").required("inputs").required("redis").required("sources");
        assertEquals(2, sources.size());
        ((ObjectNode) sources.get(0)).put("listName", first.key());
        ((ObjectNode) sources.get(1)).put("listName", second.key());
        run.evidence.record("owned-fixture", owned);
        scenario.create(owned, run.scenarios).expect(201);
        String templatePath = "templates/http/acceptance-dataset.yaml";
        String template = run.scenarios.readTemplate(target.lifecycle().fixture().templateId(), templatePath);
        run.scenarios.writeTemplate(scenarioId, templatePath, template);
        assertEquals(template, run.scenarios.readTemplate(scenarioId, templatePath));
        String sutId = target.lifecycle().fixture().sutId();
        String sut = run.scenarios.readSutRaw(target.lifecycle().fixture().templateId(), sutId);
        run.scenarios.writeSutRaw(scenarioId, sutId, sut);
        assertEquals(sut, run.scenarios.readSutRaw(scenarioId, sutId));
        try {
          swarm.create(SwarmCreateRequest.of(scenarioId, UUID.randomUUID().toString(), false,
              sutId, null, NetworkMode.DIRECT, null));
        } catch (io.pockethive.acceptance.api.ApiException failure) {
          run.evidence.record("create-rejected", failure.response());
          throw failure;
        }
        var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
            Set.of(BeeRoles.GENERATOR, BeeRoles.REQUEST_BUILDER, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR),
            "prepared-workers", (state, observedWorkers) -> state.workloadState() == WorkloadState.STOPPED
                && observedWorkers.stream().allMatch(worker -> worker.required("enabled").isBoolean()
                    && !worker.required("enabled").booleanValue()));
        first.seed(records.get(0).toString());
        second.seed(records.get(1).toString());
        java.util.List<WorkItem> samples;
        try (var tap = run.newTap()) {
          tap.open(swarm.id(), run.target.fixture().tap());
          swarm.start();
          samples = tap.awaitSamples(run.target.fixture().samples());
        } catch (Exception | AssertionError failure) {
          try {
            run.evidence.record("capture-failure-state", run.swarms.state(swarm.id()));
            run.evidence.record("capture-failure-journal",
                run.journal.read(swarm.id(), swarm.runId(), run.target.limits().request()));
          } catch (Exception diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
          throw failure;
        }
        var instances = workers.stream().collect(Collectors.toMap(
            worker -> worker.required("role").textValue(), worker -> worker.required("instance").textValue()));
        var observed = new HashSet<JsonNode>();
        assertEquals(2, samples.stream().map(WorkItem::messageId).distinct().count());
        for (var item : samples) {
          // Redis input and Generator each append a step under the generator identity.
          var generated = steps(item, BeeRoles.GENERATOR, instances, 2);
          JsonNode payload = json.readTree(generated.getFirst().payload());
          assertEquals(payload, json.readTree(generated.getLast().payload()), "Generator preserves Redis payload");
          assertTrue(expected.contains(payload), "Unexpected generated payload: " + payload);
          assertTrue(observed.add(payload), "Duplicate dataset record: " + payload);
          var built = steps(item, BeeRoles.REQUEST_BUILDER, instances, 1).getFirst();
          var request = json.readValue(built.payload(), HttpRequestEnvelope.class).request();
          assertEquals("POST", request.method());
          assertEquals("/api/test", request.path());
          assertEquals("application/json", request.headers().get("Content-Type"));
          assertEquals("rendered", request.headers().get("X-Acceptance-Template"));
          assertEquals(payload, json.valueToTree(request.body()));
          HttpWorkAssertions.requireSuccessfulResponse(item, swarm.id(), instances.get(BeeRoles.PROCESSOR),
              run.target.fixture().expectedResponse());
        }
        assertEquals(expected, observed);
        swarm.stop();
        swarm.remove();
      }
    }
  }
  private static java.util.List<WorkStep> steps(WorkItem item, String role, Map<String, String> instances, int count) {
    var matches = StreamSupport.stream(item.steps().spliterator(), false)
        .filter(step -> role.equals(step.headers().get(WorkItem.STEP_SERVICE_HEADER))).toList();
    assertEquals(count, matches.size(), "FULL history steps for " + role);
    for (var step : matches) assertEquals(instances.get(role), step.headers().get(WorkItem.STEP_INSTANCE_HEADER));
    return matches;
  }
}
