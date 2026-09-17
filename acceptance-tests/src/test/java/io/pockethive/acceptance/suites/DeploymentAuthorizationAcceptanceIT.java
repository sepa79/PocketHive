package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify deployment refresh/reset authorization with an owned swarm as a state witness.
 * Must not: execute an allowed reset, infer sync completion from 202 or change registry state directly.
 * Contract: docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
@Tag("auth-provisioned")
class DeploymentAuthorizationAcceptanceIT {
  @Test void folderAdminCannotRefreshOrResetButDeploymentAdminCanRefresh() throws Exception {
    try (var fixture = AuthFixture.open("deployment-auth"); var user = fixture.newUser()) {
      var grants = fixture.folderAdminGrants();
      user.provision(grants);
      try (var actor = fixture.login(user, "deployment-denied", grants); var swarm = fixture.newSwarm(fixture.admin)) {
        var observer = new SwarmApi(fixture.admin.http, fixture.admin.token);
        swarm.create(fixture.request(fixture.target.scenarioId()));
        var before = observer.state(swarm.id());
        fixture.admin.evidence.record("before-deployment-denials", before);
        for (String action : java.util.List.of("refresh", "reset")) {
          String path = ApiSurface.ORCHESTRATOR.publicPath("/api/control-plane/" + action);
          var denied = actor.http.request("POST", path, null, actor.token);
          actor.evidence.record(action + "-denied", denied);
          denied.expect(403);
          var after = observer.state(swarm.id());
          fixture.admin.evidence.record("after-" + action + "-denied", after);
          assertEquals(swarm.runId(), after.runId());
          assertEquals(before.controllerState(), after.controllerState());
          assertEquals(before.workloadIntent(), after.workloadIntent());
          assertEquals(before.workloadState(), after.workloadState());
          assertNull(after.activeOperation());
        }
        var response = fixture.admin.http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/control-plane/refresh"),
            null, fixture.admin.token);
        fixture.admin.evidence.record("deployment-refresh", response);
        var receipt = fixture.admin.http.tree(response.expect(202));
        assertEquals("REFRESH", receipt.required("mode").textValue());
        assertFalse(receipt.required("correlationId").asText().isBlank());
        assertEquals(swarm.runId(), observer.state(swarm.id()).runId());
        swarm.remove();
      }
    }
  }
}
