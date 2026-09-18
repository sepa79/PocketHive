package io.pockethive.acceptance.api;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Responsibility: project API links onto public ingress paths and encode opaque path identifiers.
 * Must not: infer backend ports or switch to an alternative service endpoint.
 * Contract: RESP-ACCEPTANCE-HTTP — docs/architecture/acceptance-tests.md#resp-acceptance-http.
 */
public enum ApiSurface {
  ORCHESTRATOR("/orchestrator"), SCENARIO_MANAGER("/scenario-manager"), AUTH("/auth-service"),
  NETWORK_PROXY_MANAGER("/network-proxy-manager"), TCP_MOCK("/tcp-mock"), REDIS_COMMANDER("/redis"), GRAFANA("/grafana");

  private final String prefix;
  ApiSurface(String prefix) { this.prefix = prefix; }

  public static String pathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  public String publicPath(String servicePath) {
    URI link = URI.create(servicePath);
    if (link.isAbsolute() || link.getRawAuthority() != null || link.getFragment() != null
        || !servicePath.startsWith("/") || !link.normalize().equals(link)) {
      throw new IllegalArgumentException("Expected a rooted service-relative API path: " + servicePath);
    }
    return prefix + servicePath;
  }
}
