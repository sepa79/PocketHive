package io.pockethive.acceptance.api;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: map public Scenario Manager folder requests/readbacks.
 * Must not: resolve filesystem locations, decide grants or clean up test resources.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#scenario-and-swarm-authorization-au-7au-12.
 */
public final class ScenarioFolderApi {
  private static final String PATH = ApiSurface.SCENARIO_MANAGER.publicPath("/scenarios/folders");
  private final PocketHiveHttp http;
  private final String token;
  public ScenarioFolderApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public List<String> folders() throws IOException, InterruptedException {
    return Arrays.asList(http.decode(http.request("GET", PATH, null, token).expect(200), String[].class));
  }
  public ApiResponse create(String path) throws IOException, InterruptedException {
    return http.request("POST", PATH, Map.of("path", path), token);
  }
  public ApiResponse delete(String path) throws IOException, InterruptedException {
    return http.request("DELETE", PATH + "?path=" + ApiSurface.pathSegment(path), null, token);
  }
}
