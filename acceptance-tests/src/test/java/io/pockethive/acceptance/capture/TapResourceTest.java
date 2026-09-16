package io.pockethive.acceptance.capture;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.config.HttpFixture;
import io.pockethive.acceptance.config.WaitLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemContractException;
import io.pockethive.work.api.WorkItemJsonCodec;
import java.nio.charset.StandardCharsets;
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
  private final HttpFixture fixture = new HttpFixture("fixture", "sut", BeeRoles.PROCESSOR,
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
        tap.open("test-swarm", fixture);
        assertEquals(List.of("one", "two"), tap.awaitSamples(2).stream().map(WorkItem::messageId).toList());
      }
    }
  }
  @Test void malformedWorkItemFailsAndStillClosesTheTap() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request())) {
      ingress.reply("POST", "/orchestrator/api/debug/taps", 200, snapshot(List.of()))
          .reply("GET", "/orchestrator/api/debug/taps/test-tap", 200, snapshot(List.of(Map.of("payload", "{}"))));
      closure(ingress);
      var failure = assertThrows(WorkItemContractException.class, () -> {
        try (var evidence = new RunEvidence(reports, "invalid");
          var tap = new TapResource(new DebugTapApi(http, ""), limits, evidence)) {
          tap.open("test-swarm", fixture);
          tap.awaitSamples(2);
        }
      });
      assertEquals(0, failure.getSuppressed().length);
    }
  }
}
