package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.capture.*;
import io.pockethive.acceptance.config.*;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.SwarmResource;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify the scoped management endpoint matrix against one owned running swarm.
 * Must not: calculate grants, merge CP state, delete journal archives or implement operation/capture cleanup.
 * Contract: docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
@Tag("auth-swarm-management")
class SwarmAuthorizationAcceptanceIT {
  @Test void scopedManagementUsesCanonicalOperationsAndSeparatesReadFromManage() throws Exception {
    var target = TargetLoader.loadSwarmAuthorization(TargetLoader.selectedFile());
    try (var f = AuthFixture.open("swarm-auth-admin", target.auth()); var managerUser = f.newUser();
         var runnerUser = f.newUser(); var deniedUser = f.newUser()) {
      managerUser.provision(f.folderAdminGrants()); runnerUser.provision(f.bundleRunnerGrants()); deniedUser.provision(List.of());
      try (var manager = f.login(managerUser, "swarm-manager", f.folderAdminGrants());
           var runner = f.login(runnerUser, "swarm-runner", f.bundleRunnerGrants());
           var denied = f.login(deniedUser, "swarm-no-grants", List.of()); var swarm = f.newSwarm(f.admin)) {
        var create = SwarmCreateRequest.of(f.target.scenarioId(), UUID.randomUUID().toString(), false,
            null, null, NetworkMode.DIRECT, null); // Explicit no-SUT fixture for network conflict.
        swarm.create(create, new SwarmApi(runner.http, runner.token));
        var started = swarm.start();
        String controller = started.target().instance();
        assertEquals(BeeRoles.SWARM_CONTROLLER, started.target().role());
        var management = new SwarmManagementApi(manager.http, manager.token);
        var update = swarm.managerEnabled(management, controller, true);
        assertEquals(controller, update.target().instance());
        var configured = swarm.controllerConfig(management, controller, Map.of("enabled", true));
        assertEquals(controller, configured.target().instance());
        assertEquals(BeeRoles.SWARM_CONTROLLER, configured.target().role());
        var observer = new SwarmApi(f.admin.http, f.admin.token);
        var before = observer.state(swarm.id());
        assertEquals(WorkloadState.RUNNING, before.workloadState());
        var conflict = manager.http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/swarms/"
            + ApiSurface.pathSegment(swarm.id()) + "/network"),
            Map.of("networkMode", NetworkMode.DIRECT, "idempotencyKey", UUID.randomUUID().toString(),
                "notes", "acceptance missing SUT"), manager.token);
        manager.evidence.record("network-conflict", conflict); conflict.expect(409);
        assertTrue(manager.http.tree(conflict).toString().contains("no bound sutId"));
        var after = observer.state(swarm.id());
        manager.evidence.record("after-network-conflict", after);
        assertEquals(swarm.runId(), after.runId());
        assertEquals(before.workloadIntent(), after.workloadIntent());
        assertEquals(before.workloadState(), after.workloadState());
        assertNull(after.activeOperation());
        verifyTap(target, swarm, manager, runner, denied);
        verifyJournal(f, swarm, manager);
        swarm.stop(); swarm.remove();
      }
    }
  }

  private static void verifyTap(SwarmAuthorizationTarget target, SwarmResource swarm, ApiRun manager,
                                ApiRun runner, ApiRun denied) throws Exception {
    var limits = target.auth().limits();
    var managerApi = new DebugTapApi(manager.http, manager.token);
    var runnerApi = new DebugTapApi(runner.http, runner.token);
    try (var tap = new TapResource(managerApi,
        new WaitLimits(limits.request(), limits.operation(), limits.request(), limits.poll()), manager.evidence)) {
      tap.open(swarm.id(), target.tap());
      assertEquals(tap.id(), managerApi.read(tap.id(), limits.request()).required("tapId").textValue());
      var readable = runnerApi.read(tap.id(), limits.request());
      runner.evidence.record("tap-read-allowed", readable);
      assertEquals(tap.id(), readable.required("tapId").textValue());
      var readDenied = assertThrows(ApiException.class,
          () -> new DebugTapApi(denied.http, denied.token).read(tap.id(), limits.request()));
      denied.evidence.record("tap-read-denied", readDenied.response()); readDenied.response().expect(403);
      var closeDenied = assertThrows(ApiException.class, () -> runnerApi.close(tap.id()));
      runner.evidence.record("tap-close-denied", closeDenied.response()); closeDenied.response().expect(403);
      assertEquals(tap.id(), managerApi.read(tap.id(), limits.request()).required("tapId").textValue());
    }
  }

  private static void verifyJournal(AuthFixture f, SwarmResource swarm, ApiRun manager) throws Exception {
    var journal = new SwarmJournalApi(manager.http, manager.token);
    var timeline = journal.read(swarm.id(), swarm.runId(), f.target.limits().request());
    manager.evidence.record("owned-journal", timeline);
    assertTrue(timeline.isArray()); assertFalse(timeline.isEmpty());
    var initial = awaitRun(f, swarm, manager, false);
    assertEquals(BooleanNode.FALSE, initial.required("pinned"));
    var metadata = Map.<String, Object>of("testPlan", "acceptance-owned-authorization", "description", swarm.id(),
        "tags", List.of("acceptance", "auth"));
    var initialMetadata = awaitSummary(f, swarm);
    f.admin.evidence.record("metadata-before-denial", initialMetadata);
    var denied = journal.metadata(swarm.runId(), metadata);
    manager.evidence.record("metadata-denied", denied); denied.expect(403);
    var afterDenial = awaitSummary(f, swarm);
    f.admin.evidence.record("metadata-after-denial", afterDenial);
    for (String field : List.of("testPlan", "description", "tags")) {
      assertEquals(initialMetadata.get(field), afterDenial.get(field), "Denied metadata mutation changed " + field);
    }
    var adminJournal = new SwarmJournalApi(f.admin.http, f.admin.token);
    var written = adminJournal.metadata(swarm.runId(), metadata);
    f.admin.evidence.record("metadata-written", written); written.expect(200);
    var saved = awaitSummary(f, swarm);
    f.admin.evidence.record("metadata-readback", saved);
    assertEquals(metadata.get("testPlan"), saved.required("testPlan").textValue());
    assertEquals(swarm.id(), saved.required("description").textValue());
    assertEquals(List.of("acceptance", "auth"), java.util.stream.StreamSupport.stream(saved.required("tags").spliterator(), false)
        .map(JsonNode::textValue).toList());
    var pinned = journal.pin(swarm.id(), swarm.runId(), "FULL", "acceptance-owned-" + swarm.id());
    manager.evidence.record("retained-journal-pin", pinned); pinned.expect(200);
    var receipt = manager.http.tree(pinned);
    assertEquals(swarm.id(), receipt.required("swarmId").textValue());
    assertEquals(swarm.runId(), receipt.required("runId").textValue());
    assertEquals("FULL", receipt.required("mode").textValue());
    assertFalse(receipt.required("captureId").asText().isBlank());
    assertTrue(receipt.required("entries").longValue() > 0);
    awaitRun(f, swarm, manager, true);
  }

  private static JsonNode awaitSummary(AuthFixture f, SwarmResource swarm) throws Exception {
    var journal = new SwarmJournalApi(f.admin.http, f.admin.token);
    var deadline = new Deadline(f.target.limits().operation(), "Owned journal summary " + swarm.runId());
    while (true) {
      var runs = journal.runSummaries(deadline.remaining());
      assertTrue(runs.isArray());
      var matching = java.util.stream.StreamSupport.stream(runs.spliterator(), false)
          .filter(row -> swarm.id().equals(row.path("swarmId").textValue()) && swarm.runId().equals(row.path("runId").textValue())).toList();
      assertTrue(matching.size() <= 1);
      if (matching.size() == 1) return matching.getFirst();
      deadline.pause(f.target.limits().poll());
    }
  }

  private static JsonNode awaitRun(AuthFixture f, SwarmResource swarm, ApiRun manager, boolean pinned) throws Exception {
    var journal = new SwarmJournalApi(manager.http, manager.token);
    var deadline = new Deadline(f.target.limits().operation(), "Owned journal run " + swarm.runId());
    while (true) {
      var runs = journal.runs(swarm.id(), deadline.remaining());
      assertTrue(runs.isArray());
      var matching = java.util.stream.StreamSupport.stream(runs.spliterator(), false)
          .filter(row -> swarm.runId().equals(row.path("runId").textValue())).toList();
      assertTrue(matching.size() <= 1);
      if (matching.size() == 1 && BooleanNode.valueOf(pinned).equals(matching.getFirst().path("pinned"))) {
        manager.evidence.record(pinned ? "pinned-run-readback" : "run-before-pin", matching.getFirst());
        return matching.getFirst();
      }
      deadline.pause(f.target.limits().poll());
    }
  }
}
