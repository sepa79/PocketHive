package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.auth.contract.*;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Responsibility: verify viewer/runner read access and write denial for shared network settings.
 * Must not: change grants, calculate network settings or implement rollback.
 * Contract: docs/architecture/acceptance-tests.md#network-access-acceptance-slice — AU-8/AU-13.
 */
@Tag("auth-network")
class NetworkAccessAcceptanceIT {
  @ParameterizedTest(name = "viewer cannot write {0}")
  @ValueSource(strings = {"/network-profiles/raw", "/sut-environments/raw"})
  void viewerReadsButCannotWriteSharedConfig(String servicePath) throws Exception {
    var target = TargetLoader.loadNetworkAccess(TargetLoader.selectedFile());
    try (var viewer = ApiRun.open(target.api(), "network-viewer-raw")) {
      ActorAssertions.requireGrants(viewer, target.api().username(), List.of(viewGrant()));
      String path = ApiSurface.SCENARIO_MANAGER.publicPath(servicePath);
      var before = viewer.http.request("GET", path, null, viewer.token, "text/plain");
      viewer.evidence.record("before", before);
      assertFalse(before.expect(200).body().isBlank(), "Raw fixture must contain replayable configuration");
      var denied = viewer.http.requestText("PUT", path, before.body(), viewer.token);
      viewer.evidence.record("write-denial", denied);
      var after = viewer.http.request("GET", path, null, viewer.token, "text/plain");
      viewer.evidence.record("after", after);
      assertAll(() -> denied.expect(403), () -> after.expect(200),
          () -> assertEquals(before.body(), after.body(), "Denied write must preserve raw configuration"));
    }
  }

  @Test void runnerCannotChangeManualOverrideAndViewerCanReadIt() throws Exception {
    var target = TargetLoader.loadNetworkAccess(TargetLoader.selectedFile());
    var runnerTarget = new ApiTarget(target.api().ingress(), target.runnerUsername(),
        target.api().requestTimeout(), target.api().evidenceDirectory());
    try (var viewer = ApiRun.open(target.api(), "network-viewer-override");
         var runner = ApiRun.open(runnerTarget, "network-runner-override")) {
      ActorAssertions.requireGrants(viewer, target.api().username(), List.of(viewGrant()));
      ActorAssertions.requireGrants(runner, target.runnerUsername(), List.of(viewGrant(),
          new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
              PocketHiveResourceTypes.FOLDER, target.runnerFolder())));
      String path = ApiSurface.NETWORK_PROXY_MANAGER.publicPath("/api/network/manual-override");
      var before = viewer.http.request("GET", path, null, viewer.token);
      viewer.evidence.record("before", before);
      var state = viewer.http.tree(before.expect(200));
      assertTrue(state.isObject(), "Manual override must be an object");
      assertTrue(state.required("enabled").isBoolean());
      ObjectNode request = ((ObjectNode) state).deepCopy();
      request.remove("appliedAt");
      var denied = runner.http.request("PUT", path, request, runner.token);
      runner.evidence.record("write-denial", denied);
      var after = viewer.http.request("GET", path, null, viewer.token);
      viewer.evidence.record("after", after);
      assertAll(() -> denied.expect(403), () -> after.expect(200),
          () -> assertEquals(state, viewer.http.tree(after), "Denied write must preserve override status"));
    }
  }

  private static AuthGrantDto viewGrant() {
    return new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
        PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL);
  }
}
