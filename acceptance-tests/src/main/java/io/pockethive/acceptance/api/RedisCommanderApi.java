package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/**
 * Responsibility: map exact-key list fixture operations through public Redis Commander HTTP.
 * Must not: execute console commands, infer a connection, normalize display values or own cleanup.
 * Contract: RESP-ACCEPTANCE-REDIS-FIXTURE — docs/architecture/acceptance-tests.md#redis-fixture-preparation-da-prerequisite.
 */
public final class RedisCommanderApi {
  private final PocketHiveHttp http;
  private final String connectionId;
  public RedisCommanderApi(PocketHiveHttp http, String connectionId) {
    this.http = http;
    this.connectionId = connectionId;
  }
  public JsonNode read(String key) throws IOException, InterruptedException {
    var value = http.tree(http.request("GET", path(key), null, "").expect(200));
    if (!key.equals(value.required("key").textValue())) throw new AssertionError("Redis response has a different key");
    if (!value.required("type").isTextual()) throw new AssertionError("Redis response has no textual type");
    return value;
  }
  public ApiResponse createList(String key, String payload) throws IOException, InterruptedException {
    return http.request("POST", path(key), Map.of("keyType", "list", "stringValue", payload), "", "text/plain");
  }
  public ApiResponse delete(String key) throws IOException, InterruptedException {
    return http.request("POST", path(key) + "?action=delete", Map.of(), "", "text/plain");
  }
  private String path(String key) {
    return ApiSurface.REDIS_COMMANDER.publicPath("/apiv2/key/" + ApiSurface.pathSegment(connectionId)
        + "/" + ApiSurface.pathSegment(key));
  }
}
