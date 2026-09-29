package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.OperationLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.lifecycle.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RedisDatasetResourcesTest {
  @TempDir Path reports;
  private enum Outcome { NOT_CREATED, REJECTED_CREATE, UNCONFIRMED_CREATE, FAILED_REMOVE, TERMINAL_REMOVE_FAILURE, REMOVED, BODY_FAILURE }

  @ParameterizedTest @EnumSource(Outcome.class)
  void retainsDependenciesUntilSwarmCleanupIsConfirmed(Outcome outcome) throws Exception {
    var limits = new OperationLimits(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(1));
    String scenarioPath = "/scenario-manager/scenarios/owned";
    String swarmPath = "/orchestrator/api/swarms/" + SWARM;
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "dataset")) {
      var scenarios = new ScenarioApi(http, "");
      var swarms = new SwarmApi(http, "");
      var redis = new RedisCommanderApi(http, "connection");
      var first = new RedisListResource(redis, evidence);
      var second = new RedisListResource(redis, evidence);
      var lists = new java.util.ArrayList<RedisListResource>(List.of(first, second));
      for (int i = 0; i < 5; i++) lists.add(new RedisListResource(redis, evidence));
      var scenario = new ScenarioResource("owned", scenarios, evidence);
      var swarm = new SwarmResource(SWARM, swarms, new OperationAwaiter(swarms, limits, evidence), limits);
      ingress.reply("GET", scenarioPath, 404, Map.of())
          .reply("POST", "/scenario-manager/scenarios", 201, Map.of("id", "owned"));
      for (var list : lists) seed(ingress, list.key());
      var create = receipt();
      switch (outcome) {
        case NOT_CREATED -> { }
        case REJECTED_CREATE -> ingress.reply("POST", swarmPath + "/create", 400, Map.of());
        case UNCONFIRMED_CREATE -> ingress.reply("POST", swarmPath + "/create", 503, Map.of());
        default -> {
          ingress.reply("POST", swarmPath + "/create", 202, create)
              .reply("GET", "/orchestrator" + create.operationUrl(), 200, succeeded(create, OperationType.CREATE));
          if (outcome == Outcome.FAILED_REMOVE) {
            ingress.reply("POST", swarmPath + "/remove", 503, Map.of());
          } else {
            var seed = receipt();
            var removal = new AtomicReference<ControlResponse>();
            ingress.replyWith("POST", swarmPath + "/remove", 202, body -> {
              var response = new ControlResponse(seed.correlationId(), body.required("idempotencyKey").textValue(),
                  seed.operationUrl(), seed.outcomeTopic(), seed.timeoutMs());
              removal.set(response);
              return response;
            }).replyWith("GET", "/orchestrator" + seed.operationUrl(), 200,
                ignored -> outcome == Outcome.TERMINAL_REMOVE_FAILURE
                    ? operation(removal.get(), OperationType.REMOVE, OperationState.FAILED,
                        Map.of("errors", List.of("worker still present")))
                    : succeeded(removal.get(), OperationType.REMOVE));
            if (outcome != Outcome.TERMINAL_REMOVE_FAILURE) ingress.reply("GET", swarmPath, 404, Map.of());
          }
        }
      }
      boolean retain = outcome == Outcome.UNCONFIRMED_CREATE || outcome == Outcome.FAILED_REMOVE
          || outcome == Outcome.TERMINAL_REMOVE_FAILURE;
      if (!retain) {
        ingress.reply("GET", scenarioPath, 200, Map.of("id", "owned"))
            .reply("DELETE", scenarioPath, 204, Map.of()).reply("GET", scenarioPath, 404, Map.of());
        for (var list : lists.reversed()) delete(ingress, list.key());
      }
      var bodyFailure = new AssertionError("original test assertion");
      Throwable failure = null;
      try (var dependencies = new RedisDatasetResources(swarm, scenario, lists, evidence); swarm) {
        scenario.create(new ObjectMapper().valueToTree(Map.of("id", "owned")), scenarios).expect(201);
        for (var list : lists) list.seed("payload");
        if (outcome != Outcome.NOT_CREATED) swarm.create(createRequest(create));
        if (outcome == Outcome.BODY_FAILURE) throw bodyFailure;
      } catch (Exception | AssertionError observed) { failure = observed; }
      assertEquals(!retain, swarm.permitsDependentCleanup());
      Path retained = evidence.directory().resolve("retained-dataset-resources.json");
      assertEquals(retain, Files.exists(retained));
      if (retain) {
        if (outcome == Outcome.TERMINAL_REMOVE_FAILURE) {
          assertInstanceOf(AssertionError.class, failure);
          assertTrue(failure.getMessage().contains("ended FAILED"));
        } else {
          assertInstanceOf(ApiException.class, failure);
          assertEquals(503, ((ApiException) failure).response().status());
        }
        assertTrue(List.of(failure.getSuppressed()).stream().anyMatch(problem ->
            problem.getMessage().contains(first.key()) && problem.getMessage().contains(second.key())
                && problem.getMessage().contains("owned") && problem.getMessage().contains(SWARM)));
        var retainedIds = new ObjectMapper().readTree(retained.toFile());
        assertEquals(SWARM, retainedIds.required("swarmId").textValue());
        assertEquals("owned", retainedIds.required("scenarioId").textValue());
        assertEquals(lists.stream().map(RedisListResource::key).toList(), new ObjectMapper().convertValue(retainedIds.required("redisKeys"), List.class));
      } else if (outcome == Outcome.BODY_FAILURE) {
        assertSame(bodyFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
      } else if (outcome == Outcome.REJECTED_CREATE) {
        assertInstanceOf(ApiException.class, failure);
        assertEquals(400, ((ApiException) failure).response().status());
        assertEquals(0, failure.getSuppressed().length);
      } else assertNull(failure);
    }
  }
  private static String path(String key) { return "/redis/apiv2/key/connection/" + key; }
  private static void seed(ScriptedIngress ingress, String key) {
    ingress.reply("GET", path(key), 200, Map.of("key", key, "type", "none"))
        .replyText("POST", path(key), 200, "ok")
        .reply("GET", path(key), 200, Map.of("key", key, "type", "list", "length", 1));
  }
  private static void delete(ScriptedIngress ingress, String key) {
    ingress.reply("GET", path(key), 200, Map.of("key", key, "type", "list", "length", 1))
        .replyText("POST", path(key) + "?action=delete", 200, "ok")
        .reply("GET", path(key), 200, Map.of("key", key, "type", "none"));
  }

  @org.junit.jupiter.api.Test void attemptsEveryIndependentCloseAndPreservesFailures() throws Exception {
    var limits = new OperationLimits(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(1));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "close-failures")) {
      var redis = new RedisCommanderApi(http, "connection");
      var lists = java.util.stream.IntStream.range(0, 7).mapToObj(i -> new RedisListResource(redis, evidence)).toList();
      var api = new SwarmApi(http, "");
      var swarm = new SwarmResource(SWARM, api, new OperationAwaiter(api, limits, evidence), limits);
      var scenario = new ScenarioResource("unused", new ScenarioApi(http, ""), evidence);
      for (var list : lists) {
        ingress.reply("GET", path(list.key()), 200, Map.of("key", list.key(), "type", "none"));
        list.reserveForProducer();
      }
      for (int i = 6; i >= 0; i--) {
        ingress.reply("GET", path(lists.get(i).key()), i == 6 || i == 4 ? 503 : 200,
            Map.of("key", lists.get(i).key(), "type", "none"));
      }
      var failure = assertThrows(ApiException.class,
          () -> new RedisDatasetResources(swarm, scenario, lists, evidence).close());
      assertEquals(1, failure.getSuppressed().length);
      assertInstanceOf(ApiException.class, failure.getSuppressed()[0]);
    }
  }
}
