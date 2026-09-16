package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/**
 * Responsibility: read an explicitly selected scenario through ingress.
 * Must not: create fallback fixtures or infer worker settings.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class ScenarioApi {
  private final PocketHiveHttp http;
  private final String token;
  public ScenarioApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode requireScenario(String id) throws IOException, InterruptedException {
    if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid fixture scenario id");
    return http.tree(http.request("GET", ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + id), null, token).expect(200));
  }
}
