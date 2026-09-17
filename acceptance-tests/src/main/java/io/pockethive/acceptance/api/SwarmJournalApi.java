package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;

/**
 * Responsibility: map the public journal timeline, pin and metadata endpoints for an explicit run.
 * Must not: consume CP messages, merge events or interpret runtime outcomes or own archive retention.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class SwarmJournalApi {
  private final PocketHiveHttp http;
  private final String token;
  public SwarmJournalApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode read(String swarmId, String runId, Duration budget) throws IOException, InterruptedException {
    String path = path(swarmId) + "?runId=" + ApiSurface.pathSegment(runId);
    return http.tree(http.request("GET", path, null, token, budget).expect(200));
  }
  public JsonNode runs(String swarmId, Duration budget) throws IOException, InterruptedException {
    return http.tree(http.request("GET", path(swarmId) + "/runs", null, token, budget).expect(200));
  }
  public ApiResponse pin(String swarmId, String runId, String mode, String name) throws IOException, InterruptedException {
    return http.request("POST", path(swarmId) + "/pin", java.util.Map.of("runId", runId, "mode", mode, "name", name), token);
  }
  public ApiResponse metadata(String runId, java.util.Map<String, Object> metadata) throws IOException, InterruptedException {
    return http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/journal/swarm/runs/"
        + ApiSurface.pathSegment(runId) + "/meta"), metadata, token);
  }
  public JsonNode runSummaries(Duration budget) throws IOException, InterruptedException {
    return http.tree(http.request("GET", ApiSurface.ORCHESTRATOR.publicPath("/api/journal/swarm/runs"), null, token, budget).expect(200));
  }
  private static String path(String swarmId) {
    return ApiSurface.ORCHESTRATOR.publicPath("/api/swarms/" + ApiSurface.pathSegment(swarmId) + "/journal");
  }
}
