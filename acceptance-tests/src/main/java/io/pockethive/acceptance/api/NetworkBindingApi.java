package io.pockethive.acceptance.api;

import io.pockethive.swarm.model.NetworkBinding;
import java.io.IOException;

/**
 * Responsibility: read a swarm's network binding through the public ingress.
 * Must not: resolve network addresses, mutate bindings or implement network cleanup.
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
  private static String path(String swarmId) {
    return ApiSurface.NETWORK_PROXY_MANAGER.publicPath("/api/network/bindings/" + ApiSurface.pathSegment(swarmId));
  }
}
