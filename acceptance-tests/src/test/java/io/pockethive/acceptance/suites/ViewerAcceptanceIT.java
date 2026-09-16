package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.auth.contract.*;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify deployment viewer reads and lack of run permission.
 * Must not: provision users, implement authorization or own swarm cleanup.
 * Contract: docs/architecture/acceptance-tests.md#viewer-acceptance-slice — AU-3/AU-6.
 */
@Tag("auth-viewer")
class ViewerAcceptanceIT {
  @Test void readsScenarioListDetailAndRaw() throws Exception {
    var target = TargetLoader.loadViewer(TargetLoader.selectedFile());
    try (var run = viewer(target, "viewer-scenario-reads")) {
      var response = run.http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios?includeDefunct=true"),
          null, run.token);
      run.evidence.record("scenario-list", response);
      var list = run.http.tree(response.expect(200));
      assertTrue(list.isArray(), "Scenario list must be an array");
      assertTrue(java.util.stream.StreamSupport.stream(list.spliterator(), false)
          .anyMatch(item -> target.scenarioId().equals(item.path("id").asText())), "Selected scenario must be visible");
      var detail = new ScenarioApi(run.http, run.token).requireScenario(target.scenarioId());
      run.evidence.record("scenario-detail", detail);
      assertEquals(target.scenarioId(), detail.required("id").asText());
      String id = URLEncoder.encode(target.scenarioId(), StandardCharsets.UTF_8).replace("+", "%20");
      var raw = run.http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + id + "/raw"),
          null, run.token, "text/plain");
      run.evidence.record("scenario-raw", raw);
      assertFalse(raw.expect(200).body().isBlank(), "Raw scenario must contain content");
    }
  }

  @Test void hasNoRunnableTemplates() throws Exception {
    var target = TargetLoader.loadViewer(TargetLoader.selectedFile());
    try (var run = viewer(target, "viewer-runnable-templates")) {
      var response = run.http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/api/templates"), null, run.token);
      run.evidence.record("runnable-templates", response);
      var templates = run.http.tree(response.expect(200));
      assertTrue(templates.isArray(), "Runnable templates must be an array");
      assertEquals(0, templates.size(), "Viewer must have no runnable templates");
    }
  }

  @Test void cannotCreateSwarm() throws Exception {
    var target = TargetLoader.loadViewer(TargetLoader.selectedFile());
    var adminTarget = new ApiTarget(target.api().ingress(), target.cleanupUsername(),
        target.api().requestTimeout(), target.api().evidenceDirectory());
    try (var run = viewer(target, "viewer-create-denied");
         var admin = ApiRun.open(adminTarget, "viewer-create-observer")) {
      requireGrant(admin, target.cleanupUsername(), PocketHivePermissionIds.ALL);
      new ScenarioApi(admin.http, admin.token).requireScenario(target.scenarioId());
      var observer = new SwarmApi(admin.http, admin.token);
      try (var swarm = new SwarmResource("acceptance-viewer-" + UUID.randomUUID(), observer,
          new OperationAwaiter(observer, target.limits(), admin.evidence), target.limits())) {
        observer.requireAbsent(swarm.id());
        var request = SwarmCreateRequest.of(target.scenarioId(), UUID.randomUUID().toString(), false,
            target.sutId(), null, NetworkMode.DIRECT, null);
        var denied = assertThrows(ApiException.class,
            () -> swarm.create(request, new SwarmApi(run.http, run.token)));
        run.evidence.record("create-denial", denied.response());
        assertEquals("POST", denied.response().method(), "Denial must belong to CREATE, not operation observation");
        denied.response().expect(403);
        observer.requireAbsent(swarm.id());
      }
    }
  }

  private static ApiRun viewer(ViewerTarget target, String name) throws Exception {
    var run = ApiRun.open(target.api(), name);
    try {
      requireGrant(run, target.api().username(), PocketHivePermissionIds.VIEW);
      return run;
    } catch (Exception | Error failure) {
      try (run) { throw failure; }
    }
  }

  private static void requireGrant(ApiRun run, String username, String permission) throws Exception {
    var profile = new AuthApi(run.http).profile(run.token);
    run.evidence.record("actor-profile", profile);
    assertEquals(username, profile.username());
    assertTrue(profile.active(), "Test actor must be active");
    assertEquals(List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, permission,
        PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)),
        profile.grants().stream().filter(grant -> grant.product() == AuthProduct.POCKETHIVE).toList());
  }
}
