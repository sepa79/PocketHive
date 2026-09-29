package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Map;

/**
 * Responsibility: read the public Orchestrator runtime inventory for a selected swarm run.
 * Must not: access Docker directly, resolve image names or reconstruct deployment plans.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api;
 * docs/ORCHESTRATOR-REST.md section 2.9.2 Runtime resources.
 */
public final class RuntimeInventoryApi {
  private final PocketHiveHttp http;
  private final String token;

  public RuntimeInventoryApi(PocketHiveHttp http, String token) {
    this.http = http;
    this.token = token;
  }

  public JsonNode workers(String swarmId, String runId) throws IOException, InterruptedException {
    return http.tree(http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/runtime/debug/resources/list"),
        Map.of("swarmId", swarmId, "runId", runId, "includeManagers", false), token).expect(200));
  }
}
