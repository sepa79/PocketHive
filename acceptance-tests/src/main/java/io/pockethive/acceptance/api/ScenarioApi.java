package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import io.pockethive.swarm.model.SutEnvironment;

/**
 * Responsibility: read an explicitly selected scenario and its bundle SUT through ingress.
 * Must not: validate product identifiers, create fallback fixtures or infer worker settings.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class ScenarioApi {
  private final PocketHiveHttp http;
  private final String token;
  public ScenarioApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode requireScenario(String id) throws IOException, InterruptedException {
    String path = ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + ApiSurface.pathSegment(id));
    return http.tree(http.request("GET", path, null, token).expect(200));
  }
  public SutEnvironment requireBundleSut(String scenarioId, String sutId) throws IOException, InterruptedException {
    String path = ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + ApiSurface.pathSegment(scenarioId)
        + "/suts/" + ApiSurface.pathSegment(sutId));
    return http.decode(http.request("GET", path, null, token).expect(200), SutEnvironment.class);
  }
}
