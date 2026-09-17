package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import io.pockethive.swarm.model.SutEnvironment;

/**
 * Responsibility: map scenario CRUD and read its bundle SUT through ingress.
 * Must not: validate product identifiers, create fallback fixtures or infer worker settings.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class ScenarioApi {
  private final PocketHiveHttp http;
  private final String token;
  public ScenarioApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode requireScenario(String id) throws IOException, InterruptedException {
    return http.tree(read(id).expect(200));
  }
  public SutEnvironment requireBundleSut(String scenarioId, String sutId) throws IOException, InterruptedException {
    String path = ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + ApiSurface.pathSegment(scenarioId)
        + "/suts/" + ApiSurface.pathSegment(sutId));
    return http.decode(http.request("GET", path, null, token).expect(200), SutEnvironment.class);
  }
  public ApiResponse read(String id) throws IOException, InterruptedException {
    return http.request("GET", path(id), null, token);
  }
  public ApiResponse create(JsonNode scenario) throws IOException, InterruptedException {
    return http.request("POST", ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios"), scenario, token);
  }
  public ApiResponse delete(String id) throws IOException, InterruptedException {
    return http.request("DELETE", path(id), null, token);
  }
  private static String path(String id) {
    return ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + ApiSurface.pathSegment(id));
  }
}
