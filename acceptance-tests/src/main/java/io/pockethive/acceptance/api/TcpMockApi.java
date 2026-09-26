package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Responsibility: read TCP mock mappings and request journal through the public ingress using the existing PocketHive Bearer session.
 * Must not: mutate mappings/journals, duplicate the mock DTO or fall back to direct ports.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class TcpMockApi {
  private final PocketHiveHttp http;
  private final String token;
  public TcpMockApi(PocketHiveHttp http, String token) {
    this.http = java.util.Objects.requireNonNull(http);
    if (token == null || token.isBlank()) throw new IllegalArgumentException("PocketHive token is required");
    this.token = token;
  }
  public JsonNode requests(java.time.Duration budget) throws IOException, InterruptedException {
    var response = http.request("GET", ApiSurface.TCP_MOCK.publicPath("/api/requests"),
        null, token, budget);
    var requests = http.tree(response.expect(200));
    if (!requests.isArray()) throw new AssertionError("TCP request journal must be an array");
    return requests;
  }
  public JsonNode requireMapping(String id) throws IOException, InterruptedException {
    var response = http.request("GET", ApiSurface.TCP_MOCK.publicPath("/api/mappings"), null, token);
    var mappings = http.tree(response.expect(200));
    if (!mappings.isArray()) throw new AssertionError("TCP mapping list must be an array");
    var matching = java.util.stream.StreamSupport.stream(mappings.spliterator(), false)
        .filter(mapping -> id.equals(mapping.path("id").textValue())).toList();
    if (matching.size() != 1) throw new AssertionError("Expected exactly one selected TCP mapping: " + id);
    return matching.getFirst();
  }
}
