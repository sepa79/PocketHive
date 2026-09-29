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
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files; EX-1..3.
 */
class ClearingExportAcceptanceIT {
  @Test @Tag("clearing-export")
  void twentyRecordsProduceTwoCompleteTextFiles() throws Exception { verify(ClearingExportCase.BATCH_TEXT); }

  @Test @Tag("clearing-export-xml")
  void twentyRecordsProduceTwoStructuredXmlFiles() throws Exception { verify(ClearingExportCase.STRUCTURED_XML); }

  @Test @Tag("clearing-export-streaming")
  void timeWindowFinalizesTwentyRecordsBeforeStop() throws Exception { verify(ClearingExportCase.STREAMING_TEXT); }

  private void verify(ClearingExportCase exportCase) throws Exception {
    boolean structured = exportCase == ClearingExportCase.STRUCTURED_XML;
    boolean streaming = exportCase == ClearingExportCase.STREAMING_TEXT;
    String caseName = switch (exportCase) {
      case BATCH_TEXT -> "clearing-export";
      case STRUCTURED_XML -> "clearing-export-xml";
      case STREAMING_TEXT -> "clearing-export-streaming";
    };
    var target = TargetLoader.loadExport(TargetLoader.selectedFile());
    String nonce = UUID.randomUUID().toString();
    List<String> expected = IntStream.range(0, 20).mapToObj(i -> nonce + "-record-" + i + (structured ? "<&>" : "")).toList();
    try (var run = LiveRun.open(caseName, target.lifecycle())) {
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
        if (structured) {
          String schemaPath = "clearing-schemas/acceptance/1/schema.json";
          var schema = run.scenarios.readSchema(run.target.fixture().templateId(), schemaPath);
          run.scenarios.writeSchema(scenario.id(), schemaPath, schema);
          assertEquals(schema, run.scenarios.readSchema(scenario.id(), schemaPath));
          run.evidence.record("applied-schema", schema);
        }
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
        assertEquals(structured ? "structured" : "template", config.required("mode").textValue());
        if (structured) {
          assertEquals("acceptance", config.required("schemaId").textValue());
          assertEquals("1", config.required("schemaVersion").textValue());
          assertEquals("/app/scenario/clearing-schemas", config.required("schemaRegistryRoot").textValue());
        }
        assertEquals(streaming ? 100 : 10, config.required("maxRecordsPerFile").intValue());
        assertEquals(streaming, config.required("streamingAppendEnabled").booleanValue());
        if (streaming) {
          assertEquals(15000L, config.required("streamingWindowMs").longValue());
          assertEquals(900000L, config.required("flushIntervalMs").longValue());
        }
        var layout = RuntimeFilesystemLayout.of(target.runtimeRoot().toString(), target.runtimeRoot().toString());
        var files = new ExportFiles(layout, swarm.id(), swarm.runId(), exporter.required("instance").textValue(),
            config.required("localTempSuffix").textValue());
        assertTrue(files.read().finalized().isEmpty());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = 0; i < lists.size(); i++) {
          String input = structured ? json.writeValueAsString(java.util.Map.of("id", expected.get(i), "amount", i + 1))
              : expected.get(i);
          lists.get(i).seed(input);
        }
        ExportFilesSnapshot snapshot;
        if (streaming) {
          var observations = StreamingExportObservation.duringStart(swarm::start, files::read,
              run.target.limits().capture(), run.target.limits().poll(), run.evidence);
          var completed = observations.getLast();
          snapshot = completed.files();
          run.evidence.record("export-files", snapshot);
          long windowMs = config.required("streamingWindowMs").longValue();
          long ordinaryFlushMs = config.required("flushIntervalMs").longValue();
          run.evidence.record("streaming-finalization-before-stop", java.util.Map.of(
              "elapsedSinceStartRequestMs", completed.elapsedSinceStartRequestMs(), "windowMs", windowMs,
              "ordinaryFlushMs", ordinaryFlushMs, "maxRecordsPerFile", 100, "inputRecords", expected.size()));
          StreamingExportObservation.requireWindow(observations, java.time.Duration.ofMillis(windowMs),
              java.time.Duration.ofMillis(ordinaryFlushMs));
        } else {
          swarm.start();
          var deadline = new Deadline(run.target.limits().capture(), "Complete export files for " + swarm.id());
          while (true) {
            snapshot = files.read();
            run.evidence.record("export-files", snapshot);
            if (snapshot.finalized().size() >= 2 && snapshot.pending().isEmpty()) break;
            deadline.pause(run.target.limits().poll());
          }
        }
        requireRecords(snapshot, expected, exportCase);
        swarm.stop();
        var stopped = files.read();
        run.evidence.record("export-files-after-stop", stopped);
        requireRecords(stopped, expected, exportCase);
        swarm.remove();
      }
    }
  }
  private void requireRecords(ExportFilesSnapshot files, List<String> expected, ClearingExportCase exportCase) throws Exception {
    switch (exportCase) {
      case STRUCTURED_XML -> ClearingXmlAssertions.requireRecords(files, expected);
      case BATCH_TEXT -> ClearingTextAssertions.requireRecords(files, expected);
      case STREAMING_TEXT -> ClearingTextAssertions.requireStreamingFile(files, expected);
    }
  }
}
