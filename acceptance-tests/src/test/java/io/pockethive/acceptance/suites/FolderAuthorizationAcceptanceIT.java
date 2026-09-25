package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.swarm.model.lifecycle.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: prove folder ALL manages a runner-created swarm while RUN alone cannot stop it.
 * Must not: derive authorization, synthesize operation success or implement cleanup.
 * Contract: docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
@Tag("auth-provisioned")
class FolderAuthorizationAcceptanceIT {
  @Test void folderAdminManagesTheSameSwarmThatRunnerCannotStop() throws Exception {
    try (var fixture = AuthFixture.open("folder-admin"); var runnerUser = fixture.newUser(); var managerUser = fixture.newUser()) {
      var runnerGrants = fixture.bundleRunnerGrants();
      var managerGrants = fixture.folderAdminGrants();
      runnerUser.provision(runnerGrants);
      managerUser.provision(managerGrants);
      try (var runner = fixture.login(runnerUser, "managed-runner", runnerGrants);
           var manager = fixture.login(managerUser, "folder-manager", managerGrants);
           var swarm = fixture.newSwarm(manager)) {
        var requester = new SwarmApi(runner.http, runner.token);
        var observer = new SwarmApi(fixture.admin.http, fixture.admin.token);
        observer.requireAbsent(swarm.id());
        swarm.create(fixture.request(fixture.target.scenarioId()), requester);
        swarm.start();
        var before = observer.state(swarm.id());
        fixture.admin.evidence.record("before-runner-stop", before);
        assertEquals(swarm.runId(), before.runId());
        assertEquals(WorkloadState.RUNNING, before.workloadState());
        assertNull(before.activeOperation());
        var denied = assertThrows(ApiException.class, () -> swarm.stop(requester));
        runner.evidence.record("stop-denied", denied.response());
        denied.response().expect(403);
        var after = observer.state(swarm.id());
        fixture.admin.evidence.record("after-runner-stop", after);
        assertEquals(before.runId(), after.runId());
        assertEquals(before.workloadIntent(), after.workloadIntent());
        assertEquals(before.workloadState(), after.workloadState());
        assertNull(after.activeOperation());
        swarm.stop();
        var stopped = observer.state(swarm.id());
        fixture.admin.evidence.record("folder-stopped", stopped);
        assertEquals(swarm.runId(), stopped.runId());
        assertEquals(WorkloadState.STOPPED, stopped.workloadState());
        swarm.remove();
        observer.requireAbsent(swarm.id());
      }
    }
  }
}
