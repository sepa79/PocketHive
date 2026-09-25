package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.lifecycle.ControlResponse;
import java.io.IOException;
import java.util.Map;

/**
 * Responsibility: map manager/component configuration requests and canonical acknowledgements.
 * Must not: calculate configuration, build CP messages, wait or decide operation outcomes.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class SwarmManagementApi {
  private final PocketHiveHttp http;
  private final String token;
  public SwarmManagementApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public ControlResponse managerEnabled(String swarmId, String instance, String key, boolean enabled)
      throws IOException, InterruptedException {
    var body = http.tree(http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/swarm-managers/"
        + ApiSurface.pathSegment(swarmId) + "/enabled"), Map.of("idempotencyKey", key, "enabled", enabled), token).expect(202));
    var dispatches = body.required("dispatches");
    if (!dispatches.isArray() || dispatches.size() != 1) throw new AssertionError("Expected exactly one manager dispatch");
    var dispatch = dispatches.get(0);
    var receipt = http.decode(dispatch.required("response"), ControlResponse.class);
    if (!swarmId.equals(dispatch.required("swarm").textValue())
        || !instance.equals(dispatch.required("instanceId").textValue())) {
      throw ControlReceiptMismatchException.forDispatch(swarmId + "/" + instance, receipt);
    }
    return ControlReceipts.requireKey(receipt, key);
  }
  public ControlResponse componentConfig(String swarmId, String role, String instance, String key, Map<String, Object> patch)
      throws IOException, InterruptedException {
    var response = http.request("POST", ApiSurface.ORCHESTRATOR.publicPath("/api/components/" + ApiSurface.pathSegment(role)
        + "/" + ApiSurface.pathSegment(instance) + "/config"),
        Map.of("idempotencyKey", key, "patch", patch, "swarmId", swarmId, "notes", "acceptance component configuration"), token);
    return ControlReceipts.requireKey(http.decode(response.expect(202), ControlResponse.class), key);
  }
}
