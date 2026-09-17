package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.SwarmStateView;
import java.io.IOException;
import java.time.Duration;

/**
 * Responsibility: map public swarm lifecycle requests/readbacks and match acknowledgements to submitted keys.
 * Must not: wait for convergence, decide cleanup success or build topology.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class SwarmApi {
  private final PocketHiveHttp http;
  private final String token;
  public SwarmApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }

  public ControlResponse create(String swarmId, SwarmCreateRequest request) throws IOException, InterruptedException {
    return accepted(http.request("POST", path(swarmId) + "/create", request, token), request.idempotencyKey());
  }
  public ControlResponse start(String swarmId, ControlRequest request) throws IOException, InterruptedException {
    return command(swarmId, "/start", request);
  }
  public ControlResponse stop(String swarmId, ControlRequest request) throws IOException, InterruptedException {
    return command(swarmId, "/stop", request);
  }
  public ControlResponse remove(String swarmId, ControlRequest request) throws IOException, InterruptedException {
    return command(swarmId, "/remove", request);
  }
  private ControlResponse command(String swarmId, String action, ControlRequest request) throws IOException, InterruptedException {
    return accepted(http.request("POST", path(swarmId) + action, request, token), request.idempotencyKey());
  }
  private ControlResponse accepted(ApiResponse response, String requestedKey) throws IOException {
    ControlResponse receipt = http.decode(response.expect(202), ControlResponse.class);
    if (!requestedKey.equals(receipt.idempotencyKey())) {
      throw new ControlReceiptMismatchException(requestedKey, receipt);
    }
    return receipt;
  }
  public SwarmOperation operation(String url, Duration budget) throws IOException, InterruptedException {
    return http.decode(http.request("GET", ApiSurface.ORCHESTRATOR.publicPath(url), null, token, budget).expect(200), SwarmOperation.class);
  }
  public SwarmStateView state(String swarmId) throws IOException, InterruptedException {
    return http.decode(http.request("GET", path(swarmId), null, token).expect(200), SwarmStateView.class);
  }
  public SwarmStateView state(String swarmId, Duration budget) throws IOException, InterruptedException {
    return http.decode(http.request("GET", path(swarmId), null, token, budget).expect(200), SwarmStateView.class);
  }
  public void requireAbsent(String swarmId) throws IOException, InterruptedException {
    http.request("GET", path(swarmId), null, token).expect(404);
  }
  private static String path(String swarmId) {
    if (!swarmId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid test swarm id");
    return ApiSurface.ORCHESTRATOR.publicPath("/api/swarms/" + swarmId);
  }
}
