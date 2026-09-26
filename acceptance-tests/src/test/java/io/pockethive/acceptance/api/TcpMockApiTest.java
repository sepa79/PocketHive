package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TcpMockApiTest {
  @Test void usesPocketHiveBearerForBothReadsAndDoesNotRetryUnauthorized() throws Exception {
    var headers = new java.util.ArrayList<String>();
    var paths = new java.util.ArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
    server.createContext("/tcp-mock/api/", exchange -> {
      headers.add(exchange.getRequestHeaders().getFirst("Authorization"));
      paths.add(exchange.getRequestURI().getPath());
      byte[] body = (paths.size() == 1 ? "[{\"id\":\"selected\"}]" : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(paths.size() == 1 ? 200 : 401, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try (var http = new PocketHiveHttp(java.net.URI.create("http://localhost:" + server.getAddress().getPort()), Duration.ofSeconds(1))) {
      var api = new TcpMockApi(http, "session-token");
      api.requireMapping("selected");
      assertEquals(401, assertThrows(ApiException.class, () -> api.requests(Duration.ofSeconds(1))).response().status());
      assertEquals(List.of("/tcp-mock/api/mappings", "/tcp-mock/api/requests"), paths);
      assertEquals(List.of("Bearer session-token", "Bearer session-token"), headers);
      assertThrows(IllegalArgumentException.class, () -> new TcpMockApi(http, ""));
    } finally { server.stop(0); }
  }

  @Test void selectsOnlyTheExplicitMappingAndRejectsMissingOrDuplicateIds() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      var mapping = Map.of("id", "selected", "fixedDelayMs", 5000);
      ingress.reply("GET", "/tcp-mock/api/mappings", 200, List.of(Map.of("id", "other"), mapping))
          .reply("GET", "/tcp-mock/api/mappings", 200, List.of())
          .reply("GET", "/tcp-mock/api/mappings", 200, List.of(mapping, mapping));
      var api = new TcpMockApi(http, "session-token");
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
      var api = new TcpMockApi(http, "session-token");
      assertEquals("owned", api.requests(Duration.ofSeconds(1)).get(0).required("id").textValue());
      assertThrows(AssertionError.class, () -> api.requests(Duration.ofSeconds(1)));
    }
  }
}
