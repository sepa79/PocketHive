package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.exports.ExportFiles;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.RedisDatasetResources;
import io.pockethive.acceptance.resources.RedisListResource;
import io.pockethive.acceptance.resources.ScenarioResource;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify actual finalized clearing files from twenty distinct owned inputs.
 * Must not: reconstruct output paths, implement exporter formatting or delete runtime files.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files; EX-1.
 */
@Tag("clearing-export")
class ClearingExportAcceptanceIT {
  @Test void twentyRecordsProduceTwoCompleteTextFiles() throws Exception {
    var target = TargetLoader.loadExport(TargetLoader.selectedFile());
    String nonce = UUID.randomUUID().toString();
    List<String> expected = IntStream.range(0, 20).mapToObj(i -> nonce + "-record-" + i).toList();
    try (var run = LiveRun.open("clearing-export", target.lifecycle())) {
      var lists = IntStream.range(0, 20).mapToObj(i -> new RedisListResource(run.redis(target.connectionId()), run.evidence)).toList();
      var scenario = new ScenarioResource("acceptance-clearing-" + UUID.randomUUID(), run.scenarios, run.evidence);
      var swarm = run.newSwarm();
      try (var dependencies = new RedisDatasetResources(swarm, scenario, lists, run.evidence); swarm) {
        ObjectNode owned = run.scenario.deepCopy();
        owned.put("id", scenario.id());
        owned.put("name", scenario.id());
        var generator = java.util.stream.StreamSupport.stream(owned.requiredAt("/template/bees").spliterator(), false)
            .filter(bee -> BeeRoles.GENERATOR.equals(bee.required("role").textValue())).findFirst().orElseThrow();
        var sources = ((ObjectNode) generator.requiredAt("/config/inputs/redis")).putArray("sources");
        lists.forEach(list -> sources.addObject().put("listName", list.key()).put("weight", 1));
        run.evidence.record("owned-fixture", owned);
        run.evidence.record("expected-records", expected);
        scenario.create(owned, run.scenarios).expect(201);
        String sutId = run.target.fixture().sutId();
        String sut = run.scenarios.readSutRaw(run.target.fixture().templateId(), sutId);
        run.scenarios.writeSutRaw(scenario.id(), sutId, sut);
        assertEquals(sut, run.scenarios.readSutRaw(scenario.id(), sutId));
        try {
          swarm.create(SwarmCreateRequest.of(scenario.id(), UUID.randomUUID().toString(), false,
              sutId, null, NetworkMode.DIRECT, null));
        } catch (io.pockethive.acceptance.api.ApiException failure) {
          run.evidence.record("create-rejected", failure.response());
          throw failure;
        }
        var workers = WorkerObservations.awaitConfiguredWorkers(run, swarm,
            Set.of(BeeRoles.GENERATOR, BeeRoles.CLEARING_EXPORT), "prepared-workers",
            (state, observed) -> state.workloadState() == WorkloadState.STOPPED
                && observed.stream().allMatch(w -> w.required("enabled").isBoolean() && !w.required("enabled").booleanValue()));
        var exporter = workers.stream().filter(w -> BeeRoles.CLEARING_EXPORT.equals(w.required("role").textValue()))
            .findFirst().orElseThrow();
        var config = exporter.required("config");
        assertEquals(10, config.required("maxRecordsPerFile").intValue());
        assertFalse(config.required("streamingAppendEnabled").booleanValue());
        var layout = RuntimeFilesystemLayout.of(target.runtimeRoot().toString(), target.runtimeRoot().toString());
        var files = new ExportFiles(layout, swarm.id(), swarm.runId(), exporter.required("instance").textValue(),
            config.required("localTempSuffix").textValue());
        assertTrue(files.read().finalized().isEmpty());
        for (int i = 0; i < lists.size(); i++) lists.get(i).seed(expected.get(i));
        swarm.start();
        var deadline = new Deadline(run.target.limits().capture(), "Two complete export files for " + swarm.id());
        ExportFilesSnapshot snapshot;
        while (true) {
          snapshot = files.read();
          run.evidence.record("export-files", snapshot);
          if (snapshot.finalized().size() >= 2 && snapshot.pending().isEmpty()) break;
          deadline.pause(run.target.limits().poll());
        }
        ClearingTextAssertions.requireRecords(snapshot, expected);
        swarm.stop();
        run.evidence.record("export-files-after-stop", files.read());
        ClearingTextAssertions.requireRecords(files.read(), expected);
        swarm.remove();
      }
    }
  }
}
