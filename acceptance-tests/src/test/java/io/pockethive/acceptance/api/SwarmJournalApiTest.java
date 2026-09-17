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
}
