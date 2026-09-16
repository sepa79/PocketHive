package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScenarioApiTest {
  @ParameterizedTest
  @CsvSource({
      "case.v1, case.v1",
      "case with spaces, case%20with%20spaces",
      "case/part?x=#%, case%2Fpart%3Fx%3D%23%25"
  })
  void forwardsOpaqueScenarioIdAsOneEncodedSegment(String id, String segment) throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/scenario-manager/scenarios/" + segment, 200, Map.of("id", id));
      assertEquals(id, new ScenarioApi(http, "").requireScenario(id).required("id").asText());
    }
  }
}
