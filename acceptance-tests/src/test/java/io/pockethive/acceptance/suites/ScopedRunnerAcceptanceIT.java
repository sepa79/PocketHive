package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.suites.CatalogueAssertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.auth.contract.*;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify folder-scoped RUN through catalogue, CREATE, STOP denial and deployment read APIs.
 * Must not: provision users, decide lifecycle outcomes or implement cleanup.
 * Contract: docs/architecture/acceptance-tests.md#scoped-runner-acceptance-slice — AU-4/AU-5 and RUN-only half of AU-10.
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

  @Test void cannotStopItsRunningSwarm() throws Exception {
    var target = TargetLoader.loadRunner(TargetLoader.selectedFile());
    var adminTarget = new ApiTarget(target.api().ingress(), target.cleanupUsername(),
        target.api().requestTimeout(), target.api().evidenceDirectory());
    try (var run = runner(target, "runner-stop-denied"); var admin = ApiRun.open(adminTarget, "runner-stop-cleanup")) {
      ActorAssertions.requireGrants(admin, target.cleanupUsername(), List.of(new AuthGrantDto(AuthProduct.POCKETHIVE,
          PocketHivePermissionIds.ALL, PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
      assertTrue(inFolder(entry(catalogue(admin), target.scenarioId()), target.folder()));
      var observer = new SwarmApi(admin.http, admin.token);
      var requester = new SwarmApi(run.http, run.token);
      try (var swarm = resource(observer, admin, target)) {
        observer.requireAbsent(swarm.id());
        swarm.create(request(target.scenarioId(), target.sutId()), requester);
        swarm.start();
        var before = observer.state(swarm.id());
        admin.evidence.record("before-denied-stop", before);
        assertEquals(swarm.runId(), before.runId());
        assertEquals(ControllerState.READY, before.controllerState());
        assertEquals(WorkloadIntent.RUNNING, before.workloadIntent());
        assertEquals(WorkloadState.RUNNING, before.workloadState());
        assertNull(before.activeOperation());

        var error = assertThrows(ApiException.class, () -> swarm.stop(requester));
        run.evidence.record("stop-denial", error.response());
        assertEquals("POST", error.response().method());
        error.response().expect(403);
        var after = observer.state(swarm.id());
        admin.evidence.record("after-denied-stop", after);
        assertEquals(before.runId(), after.runId());
        assertEquals(before.workloadIntent(), after.workloadIntent());
        assertEquals(before.workloadState(), after.workloadState());
        assertNull(after.activeOperation());

        swarm.stop();
        var stopped = observer.state(swarm.id());
        admin.evidence.record("after-admin-stop", stopped);
        assertEquals(swarm.runId(), stopped.runId());
        assertEquals(WorkloadIntent.STOPPED, stopped.workloadIntent());
        assertEquals(WorkloadState.STOPPED, stopped.workloadState());
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
