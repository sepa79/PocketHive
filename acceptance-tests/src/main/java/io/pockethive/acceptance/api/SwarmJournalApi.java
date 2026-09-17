package io.pockethive.acceptance.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;

/**
 * Responsibility: read the public journal timeline for an explicit swarm and run.
 * Must not: consume CP messages, merge events or interpret runtime outcomes.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class SwarmJournalApi {
  private final PocketHiveHttp http;
  private final String token;
  public SwarmJournalApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public JsonNode read(String swarmId, String runId, Duration budget) throws IOException, InterruptedException {
    String path = ApiSurface.ORCHESTRATOR.publicPath("/api/swarms/" + ApiSurface.pathSegment(swarmId)
        + "/journal?runId=" + ApiSurface.pathSegment(runId));
    return http.tree(http.request("GET", path, null, token, budget).expect(200));
  }
}
