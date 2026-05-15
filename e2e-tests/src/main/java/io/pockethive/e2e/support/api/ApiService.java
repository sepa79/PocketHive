package io.pockethive.e2e.support.api;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;

/**
 * Canonical ingress-facing API surfaces used by the E2E harness.
 */
public enum ApiService {
  ORCHESTRATOR("orchestrator"),
  SCENARIO_MANAGER("scenario-manager"),
  NETWORK_PROXY_MANAGER("network-proxy-manager");

  private final String normalizedName;

  ApiService(String normalizedName) {
    this.normalizedName = normalizedName;
  }

  public static ApiService fromDisplayName(String raw) {
    String normalized = Objects.requireNonNull(raw, "raw")
        .trim()
        .toLowerCase(Locale.ROOT)
        .replace(' ', '-');
    for (ApiService service : values()) {
      if (service.normalizedName.equals(normalized)) {
        return service;
      }
    }
    throw new IllegalArgumentException("Unsupported API service: " + raw);
  }

  public URI baseUrl(ServiceEndpoints endpoints) {
    Objects.requireNonNull(endpoints, "endpoints");
    return switch (this) {
      case ORCHESTRATOR -> endpoints.orchestratorBaseUrl();
      case SCENARIO_MANAGER -> endpoints.scenarioManagerBaseUrl();
      case NETWORK_PROXY_MANAGER -> endpoints.networkProxyManagerBaseUrl();
    };
  }
}
