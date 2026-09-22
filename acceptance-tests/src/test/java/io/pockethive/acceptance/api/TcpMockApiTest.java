package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TcpMockApiTest {
  @Test void selectsOnlyTheExplicitMappingAndRejectsMissingOrDuplicateIds() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      var mapping = Map.of("id", "selected", "fixedDelayMs", 5000);
      ingress.reply("GET", "/tcp-mock/api/mappings", 200, List.of(Map.of("id", "other"), mapping))
          .reply("GET", "/tcp-mock/api/mappings", 200, List.of())
          .reply("GET", "/tcp-mock/api/mappings", 200, List.of(mapping, mapping));
      var api = new TcpMockApi(http, "test", "pass");
      assertEquals(5000, api.requireMapping("selected").required("fixedDelayMs").intValue());
      assertThrows(AssertionError.class, () -> api.requireMapping("selected"));
      assertThrows(AssertionError.class, () -> api.requireMapping("selected"));
    }
  }

  @Test void journalIsReadOnlyAndRejectsNonArrayResponses() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      var row = Map.of("id", "owned", "message", "request", "response", "OK");
      ingress.reply("GET", "/tcp-mock/api/requests", 200, List.of(row))
          .reply("GET", "/tcp-mock/api/requests", 200, Map.of("error", "unavailable"));
      var api = new TcpMockApi(http, "test", "pass");
      assertEquals("owned", api.requests(Duration.ofSeconds(1)).get(0).required("id").textValue());
      assertThrows(AssertionError.class, () -> api.requests(Duration.ofSeconds(1)));
    }
  }
}
