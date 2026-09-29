package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.SutEndpoint;
import org.junit.jupiter.api.Test;
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

  @Test
  void readsTheSelectedBundleSutWithoutGlobalSutFallback() throws Exception {
    var sut = new SutEnvironment("sut/one", "Selected SUT", "http",
        Map.of("default", new SutEndpoint("http", "http://proxy:18090", "http://sut:8080")));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/scenario-manager/scenarios/case%2Fone/suts/sut%2Fone", 200, sut);
      assertEquals(sut, new ScenarioApi(http, "").requireBundleSut("case/one", "sut/one"));
      ingress.reply("GET", "/scenario-manager/scenarios/case%2Fone/suts/missing", 404, Map.of());
      assertThrows(ApiException.class,
          () -> new ScenarioApi(http, "").requireBundleSut("case/one", "missing"));
    }
  }

  @Test
  void copiesJsonSchemaWithoutDoubleEncodingAndSurfacesRejection() throws Exception {
    var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"schemaId\":\"owned\",\"recordMapping\":{}}");
    String path = "/scenario-manager/scenarios/owned%2Fone/schema?path=clearing-schemas%2Fcase%201%2Fschema.json";
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.replyWith("PUT", path, 204, request -> { assertEquals(schema, request); return Map.of(); });
      ingress.reply("GET", path, 200, schema);
      ingress.reply("PUT", path, 403, Map.of());
      var api = new ScenarioApi(http, "");
      api.writeSchema("owned/one", "clearing-schemas/case 1/schema.json", schema);
      assertEquals(schema, api.readSchema("owned/one", "clearing-schemas/case 1/schema.json"));
      assertThrows(ApiException.class, () -> api.writeSchema("owned/one", "clearing-schemas/case 1/schema.json", schema));
    }
  }
}
