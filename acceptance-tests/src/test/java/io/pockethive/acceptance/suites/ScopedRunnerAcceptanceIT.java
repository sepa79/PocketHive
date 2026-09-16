package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.auth.contract.*;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify folder-scoped RUN through catalogue, CREATE and deployment read APIs.
 * Must not: provision users, decide lifecycle outcomes or implement cleanup.
 * Contract: docs/architecture/acceptance-tests.md#scoped-runner-acceptance-slice — AU-4/AU-5.
 */
@Tag("auth-runner")
class ScopedRunnerAcceptanceIT {
  @Test void createsOnlyInsideItsFolder() throws Exception {
    var target = TargetLoader.loadRunner(TargetLoader.selectedFile());
    var adminTarget = new ApiTarget(target.api().ingress(), target.cleanupUsername(),
        target.api().requestTimeout(), target.api().evidenceDirectory());
    try (var run = runner(target, "runner-scope"); var admin = ApiRun.open(adminTarget, "runner-cleanup")) {
      ActorAssertions.requireGrants(admin, target.cleanupUsername(), List.of(new AuthGrantDto(AuthProduct.POCKETHIVE,
          PocketHivePermissionIds.ALL, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
      JsonNode adminCatalogue = catalogue(admin);
      assertTrue(inFolder(entry(adminCatalogue, target.scenarioId()), target.folder()), "Allowed fixture must be inside RUN scope");
      assertFalse(inFolder(entry(adminCatalogue, target.deniedScenarioId()), target.folder()), "Denied fixture must be outside RUN scope");
      JsonNode runnable = catalogue(run);
      entry(runnable, target.scenarioId());
      for (JsonNode item : runnable) {
        assertTrue(inFolder(item, target.folder()), "Runnable catalogue leaked a scenario outside RUN scope: " + item.path("id"));
        assertNotEquals(target.deniedScenarioId(), item.required("id").asText());
      }
      var observer = new SwarmApi(admin.http, admin.token);
      var requester = new SwarmApi(run.http, run.token);
      try (var allowed = resource(observer, admin, target)) {
        observer.requireAbsent(allowed.id());
        allowed.create(request(target.scenarioId(), target.sutId()), requester);
        assertEquals(allowed.runId(), observer.state(allowed.id()).runId());
      }
      try (var denied = resource(observer, admin, target)) {
        observer.requireAbsent(denied.id());
        var error = assertThrows(ApiException.class,
            () -> denied.create(request(target.deniedScenarioId(), target.sutId()), requester));
        run.evidence.record("outside-folder-denial", error.response());
        assertEquals("POST", error.response().method());
        error.response().expect(403);
        observer.requireAbsent(denied.id());
      }
    }
  }

  @Test void readsDeploymentViewApis() throws Exception {
    var target = TargetLoader.loadRunner(TargetLoader.selectedFile());
    try (var run = runner(target, "runner-deployment-reads")) {
      read(run, "capabilities", ApiSurface.SCENARIO_MANAGER, "/api/capabilities?all=true");
      read(run, "workspaces", ApiSurface.SCENARIO_MANAGER, "/scenarios/bundles/workspaces");
      read(run, "control-schema", ApiSurface.ORCHESTRATOR, "/api/control-plane/schema/control-events");
      read(run, "hive-journal", ApiSurface.ORCHESTRATOR, "/api/journal/hive/page?limit=1");
      read(run, "network-bindings", ApiSurface.NETWORK_PROXY_MANAGER, "/api/network/bindings");
      read(run, "network-proxies", ApiSurface.NETWORK_PROXY_MANAGER, "/api/network/proxies");
    }
  }

  private static ApiRun runner(RunnerTarget target, String name) throws Exception {
    var run = ApiRun.open(target.api(), name);
    try {
      ActorAssertions.requireGrants(run, target.api().username(), List.of(
          new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
              PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL),
          new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
              PocketHiveResourceTypes.FOLDER, target.folder())));
      return run;
    } catch (Exception | Error failure) {
      try (run) { throw failure; }
    }
  }

  private static JsonNode catalogue(ApiRun run) throws Exception {
    var response = run.http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/api/templates"), null, run.token);
    run.evidence.record("runnable-catalogue", response);
    var result = run.http.tree(response.expect(200));
    assertTrue(result.isArray());
    return result;
  }

  private static JsonNode entry(JsonNode catalogue, String id) {
    var matches = java.util.stream.StreamSupport.stream(catalogue.spliterator(), false)
        .filter(item -> id.equals(item.path("id").asText())).toList();
    assertEquals(1, matches.size(), "Expected one runnable fixture " + id);
    return matches.getFirst();
  }

  private static boolean inFolder(JsonNode entry, String folder) {
    String actual = entry.required("folderPath").asText();
    return actual.equals(folder) || actual.startsWith(folder + "/");
  }

  private static SwarmResource resource(SwarmApi observer, ApiRun admin, RunnerTarget target) {
    return new SwarmResource("acceptance-runner-" + UUID.randomUUID(), observer,
        new OperationAwaiter(observer, target.limits(), admin.evidence), target.limits());
  }

  private static SwarmCreateRequest request(String scenarioId, String sutId) {
    return SwarmCreateRequest.of(scenarioId, UUID.randomUUID().toString(), false, sutId, null, NetworkMode.DIRECT, null);
  }

  private static void read(ApiRun run, String name, ApiSurface surface, String path) throws Exception {
    var response = run.http.request("GET", surface.publicPath(path), null, run.token);
    run.evidence.record(name, response);
    response.expect(200);
  }
}
