package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.resources.*;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify scoped folder mutations and deployment-wide scenario CRUD authorization.
 * Must not: calculate permissions, validate descriptors, mutate existing bundles or delete files directly.
 * Contract: docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
@Tag("auth-scenario-mutations")
class ScenarioMutationAuthorizationAcceptanceIT {
  @Test void folderAdminCreatesAndDeletesWhileViewerCannotDelete() throws Exception {
    try (var f = AuthFixture.open("folder-mutations"); var managerUser = f.newUser(); var viewerUser = f.newUser()) {
      managerUser.provision(f.folderAdminGrants()); viewerUser.provision(f.viewerGrants());
      try (var manager = f.login(managerUser, "folder-writer", f.folderAdminGrants());
           var viewer = f.login(viewerUser, "folder-viewer", f.viewerGrants())) {
        var observer = new ScenarioFolderApi(f.admin.http, f.admin.token);
        var writer = new ScenarioFolderApi(manager.http, manager.token);
        String path = f.target.folder() + "/acceptance-" + UUID.randomUUID();
        f.admin.evidence.record("owned-folder", path);
        try (var folder = new ScenarioFolderResource(path, observer, f.admin.evidence)) {
          folder.create(writer).expect(204);
          assertTrue(observer.folders().contains(path));
          var denied = new ScenarioFolderApi(viewer.http, viewer.token).delete(path);
          viewer.evidence.record("folder-delete-denied", denied); denied.expect(403);
          assertTrue(observer.folders().contains(path));
          folder.delete(writer);
        }
      }
    }
  }
  @Test void onlyDeploymentAdminCreatesAndDeletesNewScenarios() throws Exception {
    try (var f = AuthFixture.open("scenario-mutations"); var user = f.newUser()) {
      user.provision(f.folderAdminGrants());
      try (var folderActor = f.login(user, "scenario-folder-actor", f.folderAdminGrants())) {
        var adminApi = new ScenarioApi(f.admin.http, f.admin.token);
        var folderApi = new ScenarioApi(folderActor.http, folderActor.token);
        var source = adminApi.requireScenario(f.target.scenarioId());
        for (boolean allowed : new boolean[] {false, true}) {
          String id = "acceptance-created-" + UUID.randomUUID();
          ObjectNode body = source.deepCopy();
          body.put("id", id); body.put("name", "Owned authorization fixture " + id);
          try (var scenario = new ScenarioResource(id, adminApi, f.admin.evidence)) {
            scenario.create(body, allowed ? adminApi : folderApi).expect(allowed ? 201 : 403);
            if (allowed) {
              var readback = adminApi.requireScenario(id);
              f.admin.evidence.record("created-scenario", readback);
              assertEquals(id, readback.required("id").textValue());
              assertEquals(body.required("name"), readback.required("name"));
              var denied = folderApi.delete(id);
              folderActor.evidence.record("scenario-delete-denied", denied); denied.expect(403);
              assertEquals(id, adminApi.requireScenario(id).required("id").textValue());
              scenario.delete(adminApi);
            } else adminApi.read(id).expect(404);
          }
        }
      }
    }
  }
}
