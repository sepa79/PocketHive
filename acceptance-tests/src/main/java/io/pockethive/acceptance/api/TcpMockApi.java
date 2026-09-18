package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Responsibility: read TCP mock mappings and request journal through the public ingress.
 * Must not: mutate mappings/journals, duplicate the mock DTO or fall back to direct ports.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class TcpMockApi {
  private final PocketHiveHttp http;
  private final String username;
  private final String password;
  public TcpMockApi(PocketHiveHttp http, String username, String password) {
    this.http = http; this.username = username; this.password = password;
  }
  public JsonNode requests(java.time.Duration budget) throws IOException, InterruptedException {
    var response = http.requestWithBasicAuth("GET", ApiSurface.TCP_MOCK.publicPath("/api/requests"),
        null, username, password, budget);
    var requests = http.tree(response.expect(200));
    if (!requests.isArray()) throw new AssertionError("TCP request journal must be an array");
    return requests;
  }
  public JsonNode requireMapping(String id) throws IOException, InterruptedException {
    var response = http.getWithBasicAuth(ApiSurface.TCP_MOCK.publicPath("/api/mappings"), username, password);
    var mappings = http.tree(response.expect(200));
    if (!mappings.isArray()) throw new AssertionError("TCP mapping list must be an array");
    var matching = java.util.stream.StreamSupport.stream(mappings.spliterator(), false)
        .filter(mapping -> id.equals(mapping.path("id").textValue())).toList();
    if (matching.size() != 1) throw new AssertionError("Expected exactly one selected TCP mapping: " + id);
    return matching.getFirst();
  }
}
