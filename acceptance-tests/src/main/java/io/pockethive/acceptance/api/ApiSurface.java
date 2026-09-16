package io.pockethive.acceptance.api;

import java.net.URI;

/**
 * Responsibility: project service-relative API links onto the documented public ingress paths.
 * Must not: infer backend ports or switch to an alternative service endpoint.
 * Contract: RESP-ACCEPTANCE-HTTP — docs/architecture/acceptance-tests.md#resp-acceptance-http.
 */
public enum ApiSurface {
  ORCHESTRATOR("/orchestrator"), SCENARIO_MANAGER("/scenario-manager"), AUTH("/auth-service");

  private final String prefix;
  ApiSurface(String prefix) { this.prefix = prefix; }

  public String publicPath(String servicePath) {
    URI link = URI.create(servicePath);
    if (link.isAbsolute() || link.getRawAuthority() != null || link.getFragment() != null
        || !servicePath.startsWith("/") || !link.normalize().equals(link)) {
      throw new IllegalArgumentException("Expected a rooted service-relative API path: " + servicePath);
    }
    return prefix + servicePath;
  }
}
