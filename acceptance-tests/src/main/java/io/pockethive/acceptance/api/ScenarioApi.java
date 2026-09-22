package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import io.pockethive.swarm.model.SutEnvironment;

/**
 * Responsibility: map scenario CRUD and bundle schema/template/SUT content through ingress.
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
  public JsonNode readSchema(String id, String schemaPath) throws IOException, InterruptedException {
    return http.tree(http.request("GET", path(id) + "/schema?path=" + ApiSurface.pathSegment(schemaPath),
        null, token).expect(200));
  }
  public void writeSchema(String id, String schemaPath, JsonNode schema) throws IOException, InterruptedException {
    http.request("PUT", path(id) + "/schema?path=" + ApiSurface.pathSegment(schemaPath), schema, token).expect(204);
  }
  public String readTemplate(String id, String templatePath) throws IOException, InterruptedException {
    return http.request("GET", templatePath(id, templatePath), null, token, "text/plain").expect(200).body();
  }
  public void writeTemplate(String id, String templatePath, String text) throws IOException, InterruptedException {
    http.requestText("PUT", templatePath(id, templatePath), text, token).expect(204);
  }
  public String readSutRaw(String id, String sutId) throws IOException, InterruptedException {
    return http.request("GET", sutPath(id, sutId), null, token, "text/plain").expect(200).body();
  }
  public void writeSutRaw(String id, String sutId, String text) throws IOException, InterruptedException {
    http.requestText("PUT", sutPath(id, sutId), text, token).expect(204);
  }
  private static String templatePath(String id, String templatePath) {
    return path(id) + "/template?path=" + ApiSurface.pathSegment(templatePath);
  }
  private static String sutPath(String id, String sutId) {
    return path(id) + "/suts/" + ApiSurface.pathSegment(sutId) + "/raw";
  }
  private static String path(String id) {
    return ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/" + ApiSurface.pathSegment(id));
  }
}
