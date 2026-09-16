package io.pockethive.acceptance.capture;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.api.ApiSurface;
import io.pockethive.acceptance.config.HttpFixture;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Responsibility: map logical debug tap requests to the public API.
 * Must not: configure broker clients or resolve resource names.
 * Contract: RESP-ACCEPTANCE-CAPTURE — docs/architecture/acceptance-tests.md#resp-acceptance-capture.
 */
public final class DebugTapApi {
  private static final String PATH = ApiSurface.ORCHESTRATOR.publicPath("/api/debug/taps");
  private final PocketHiveHttp http;
  private final String token;
  public DebugTapApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode create(String swarmId, HttpFixture fixture) throws IOException, InterruptedException {
    return http.tree(http.request("POST", PATH, Map.of(
        "swarmId", swarmId, "role", fixture.captureRole(), "direction", fixture.captureDirection(),
        "ioName", fixture.captureIoName(), "maxItems", fixture.samples(), "ttlSeconds", fixture.tapTtlSeconds()),
        token).expect(200));
  }
  public JsonNode read(String tapId, Duration budget) throws IOException, InterruptedException {
    return http.tree(http.request("GET", path(tapId), null, token, budget).expect(200));
  }
  public void close(String tapId) throws IOException, InterruptedException {
    http.request("DELETE", path(tapId), null, token).expect(200);
    http.request("GET", path(tapId) + "?drain=0", null, token).expect(404);
  }
  private static String path(String tapId) {
    if (!tapId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid tap id");
    return PATH + "/" + tapId;
  }
}
