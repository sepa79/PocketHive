package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import java.io.IOException;

/**
 * Responsibility: map canonical binding reads, apply and clear through the public ingress.
 * Must not: resolve addresses, decide mutation success, retry requests or own cleanup.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class NetworkBindingApi {
  private final PocketHiveHttp http;
  private final String token;
  public NetworkBindingApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public NetworkBinding requireBinding(String swarmId) throws IOException, InterruptedException {
    return http.decode(http.request("GET", path(swarmId), null, token).expect(200), NetworkBinding.class);
  }
  public ApiResponse requireAbsent(String swarmId) throws IOException, InterruptedException {
    return http.request("GET", path(swarmId), null, token).expect(404);
  }
  public ApiResponse bind(String swarmId, NetworkBindingRequest request) throws IOException, InterruptedException {
    return http.request("POST", path(swarmId), request, token);
  }
  public ApiResponse clear(String swarmId, NetworkBindingClearRequest request) throws IOException, InterruptedException {
    return http.request("POST", path(swarmId) + "/clear", request, token);
  }
  private static String path(String swarmId) {
    return ApiSurface.NETWORK_PROXY_MANAGER.publicPath("/api/network/bindings/" + ApiSurface.pathSegment(swarmId));
  }
}
