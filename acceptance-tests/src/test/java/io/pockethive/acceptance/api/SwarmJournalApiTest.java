package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmJournalApiTest {
  @Test void selectsTheExplicitRunAndNeverFallsBackToTheCurrentRun() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      String path = "/orchestrator/api/swarms/swarm%2Fone/journal?runId=run%3Ftwo";
      ingress.reply("GET", path, 200, List.of(Map.of("runId", "run?two")))
          .reply("GET", path, 404, Map.of());
      var api = new SwarmJournalApi(http, "");
      assertEquals("run?two", api.read("swarm/one", "run?two", Duration.ofSeconds(1)).get(0).required("runId").textValue());
      assertThrows(ApiException.class, () -> api.read("swarm/one", "run?two", Duration.ofSeconds(1)));
    }
  }
  @Test void pinAndMetadataPreserveExplicitIdentityAndDenialResponses() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/orchestrator/api/swarms/swarm%2Fone/journal/runs", 200, List.of())
          .replyWith("POST", "/orchestrator/api/swarms/swarm%2Fone/journal/pin", 200, body -> {
            assertEquals("run?two", body.required("runId").textValue());
            assertEquals("FULL", body.required("mode").textValue());
            return Map.of("captureId", "owned");
          })
          .replyWith("POST", "/orchestrator/api/journal/swarm/runs/run%3Ftwo/meta", 403, body -> {
            assertEquals("owned plan", body.required("testPlan").textValue()); return Map.of();
          })
          .reply("GET", "/orchestrator/api/journal/swarm/runs", 200, List.of());
      var api = new SwarmJournalApi(http, "token");
      assertTrue(api.runs("swarm/one", Duration.ofSeconds(1)).isArray());
      api.pin("swarm/one", "run?two", "FULL", "owned archive").expect(200);
      api.metadata("run?two", Map.of("testPlan", "owned plan")).expect(403);
      assertTrue(api.runSummaries(Duration.ofSeconds(1)).isArray());
    }
  }
}
