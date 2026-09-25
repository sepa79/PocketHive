package io.pockethive.acceptance.capture;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.acceptance.api.ApiException;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.config.WorkFixture;
import io.pockethive.acceptance.config.WaitLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemContractException;
import io.pockethive.work.api.WorkItemJsonCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TapResourceTest {
  @TempDir Path reports;
  private final WaitLimits limits = new WaitLimits(Duration.ofSeconds(1), Duration.ofSeconds(2),
      Duration.ofSeconds(1), Duration.ofMillis(1));
  private final WorkFixture fixture = new WorkFixture("fixture", "sut", BeeRoles.PROCESSOR,
      "OUT", "out", 2, 5, "{}");
  private Map<String, Object> snapshot(List<Map<String, String>> samples) {
    return Map.of("tapId", "test-tap", "swarmId", "test-swarm", "role", fixture.captureRole(),
        "direction", fixture.captureDirection(), "ioName", fixture.captureIoName(), "samples", samples);
  }
  private Map<String, String> sample(String id) {
    var observation = new ObservabilityContext("trace-" + id, List.of());
    observation.setSwarmId("test-swarm");
    var item = WorkItem.builder().messageId(id).observabilityContext(observation)
        .step("result", Map.of(WorkItem.STEP_SERVICE_HEADER, BeeRoles.PROCESSOR,
            WorkItem.STEP_INSTANCE_HEADER, "test-worker")).build();
    return Map.of("payload", new String(new WorkItemJsonCodec().toJson(item), StandardCharsets.UTF_8));
  }
  private void closure(ScriptedIngress ingress) {
    ingress.reply("DELETE", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of()))
        .reply("GET", "/orchestrator/api/debug/taps/test-tap?drain=0", 404, Map.of());
  }
  @Test void decodesCanonicalDistinctMessagesAndClosesItsTap() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request())) {
      var one = sample("one"); var two = sample("two");
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(one)))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(one, two)));
      closure(ingress);
      try (var evidence = new RunEvidence(reports, "capture");
          var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
        tap.open("test-swarm", fixture.tap());
        assertEquals(List.of("one", "two"), tap.awaitSamples(2).stream().map(WorkItem::messageId).toList());
      }
    }
  }
  @Test void malformedWorkItemFailsAndStillClosesTheTap() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request())) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(sample("one"), Map.of("payload", "{}"))));
      closure(ingress);
      var evidence = new RunEvidence(reports, "invalid");
      var failure = assertThrows(WorkItemContractException.class, () -> {
        try (evidence;
          var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
          tap.open("test-swarm", fixture.tap());
          tap.awaitSamples(2);
        }
      });
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(List.of("one"), selectedSampleIds(evidence));
    }
  }

  @Test void retainsSelectedSamplesWhenTheServerRingEvictsEarlierMessages() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "ring-eviction")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(sample("A"))))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200,
              snapshot(List.of(sample("B"), sample("C"), sample("D"))));
      closure(ingress);
      var threeSamples = new WorkFixture(fixture.templateId(), fixture.sutId(), fixture.captureRole(),
          fixture.captureDirection(), fixture.captureIoName(), 3, fixture.tapTtlSeconds(), fixture.expectedResponse());
      try (var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
        tap.open("test-swarm", threeSamples.tap());
        var selected = tap.awaitSamples(3).stream().map(WorkItem::messageId).toList();
        assertEquals(List.of("A", "B", "C"), selected);
        assertEquals(selected, selectedSampleIds(evidence));
      }
    }
  }

  @Test void timeoutPreservesPartialSelectedSamplesAndStillClosesTheTap() throws Exception {
    var slowPoll = new WaitLimits(limits.request(), limits.operation(), limits.capture(), Duration.ofSeconds(2));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "partial-timeout")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(sample("one"))));
      closure(ingress);
      var failure = assertThrows(AssertionError.class, () -> {
        try (var tap = new TapResource(new DebugTapApi(http, ""), slowPoll, evidence)) {
          tap.open("test-swarm", fixture.tap());
          tap.awaitSamples(2);
        }
      });
      assertTrue(failure.getMessage().contains("timed out"));
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(List.of("one"), selectedSampleIds(evidence));
    }
  }

  @Test void tapCloseFailureRemainsVisibleAlongsideTheTestFailure() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "failed-close")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("DELETE", "/orchestrator/api/debug/taps/test-tap", 500, Map.of("message", "close failed"));
      var primary = new AssertionError("test failure");
      var failure = assertThrows(AssertionError.class, () -> {
        try (var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
          tap.open("test-swarm", fixture.tap());
          throw primary;
        }
      });
      assertSame(primary, failure);
      assertEquals(1, failure.getSuppressed().length);
      var cleanup = assertInstanceOf(ApiException.class, failure.getSuppressed()[0]);
      assertEquals(500, cleanup.response().status());
      // No GET404 or retry is scripted: failed close cannot be converted into success.
    }
  }

  @Test void emptyWindowStillChecksTheFinalSnapshotAndClosesOnUnexpectedOutput() throws Exception {
    var quietLimits = new WaitLimits(limits.request(), limits.operation(), limits.capture(), Duration.ofSeconds(1));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "quiet-window")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(sample("unexpected"))));
      closure(ingress);
      var failure = assertThrows(AssertionError.class, () -> {
        try (var tap = new TapResource(new DebugTapApi(http, ""), quietLimits, evidence)) {
          tap.open("test-swarm", fixture.tap());
          tap.requireEmptyFor(Duration.ofMillis(300));
        }
      });
      assertTrue(failure.getMessage().contains("Unexpected output"));
    }
  }

  @Test void quietWindowCannotTreatAReadFailureAsAbsence() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "quiet-read-failure")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 500, Map.of());
      closure(ingress);
      assertThrows(ApiException.class, () -> {
        try (var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
          tap.open("test-swarm", fixture.tap());
          tap.requireEmptyFor(Duration.ofMillis(50));
        }
      });
    }
  }

  @Test void missingSamplesTimeOutAndStillCloseTheTap() throws Exception {
    var shortLimits = new WaitLimits(limits.request(), limits.operation(), Duration.ofMillis(250), Duration.ofSeconds(2));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "no-samples")) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of()));
      closure(ingress);
      var error = assertThrows(AssertionError.class, () -> {
        try (var tap = new TapResource(new DebugTapApi(http, ""), shortLimits, evidence)) {
          tap.open("test-swarm", fixture.tap());
          tap.awaitSamples(1);
        }
      });
      assertTrue(error.getMessage().contains("timed out"));
    }
  }

  private List<String> selectedSampleIds(RunEvidence evidence) throws Exception {
    try (var paths = Files.list(evidence.directory())) {
      var ids = new java.util.ArrayList<String>();
      for (Path file : paths.filter(path -> path.getFileName().toString().contains("-sample-")).sorted().toList()) {
        var sample = new ObjectMapper().readTree(file.toFile());
        ids.add(new WorkItemJsonCodec().fromJson(sample.required("payload").asText()
            .getBytes(StandardCharsets.UTF_8)).messageId());
      }
      return ids;
    }
  }

}
