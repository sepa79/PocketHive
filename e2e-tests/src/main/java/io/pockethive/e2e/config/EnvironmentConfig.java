package io.pockethive.e2e.config;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Centralises access to the environment variables that drive the PocketHive end-to-end harness.
 */
public final class EnvironmentConfig {

  public static final String ORCHESTRATOR_BASE_URL = "ORCHESTRATOR_BASE_URL";
  public static final String SCENARIO_MANAGER_BASE_URL = "SCENARIO_MANAGER_BASE_URL";
  public static final String RABBITMQ_URI = "RABBITMQ_URI";
  public static final String UI_WEBSOCKET_URI = "UI_WEBSOCKET_URI";
  public static final String SWARM_ID = "SWARM_ID";
  public static final String IDEMPOTENCY_KEY_PREFIX = "IDEMPOTENCY_KEY_PREFIX";

  private EnvironmentConfig() {
  }

  /**
   * Resolves a required environment variable as a {@link URI}.
   *
   * @param variable the environment variable to resolve
   * @return the parsed URI value
   * @throws IllegalStateException when the variable is missing or blank
   */
  public static URI requiredUri(String variable) {
    return env(variable)
        .map(value -> {
          try {
            return URI.create(value);
          } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid URI configured for " + variable + ": " + value, ex);
          }
        })
        .orElseThrow(() -> new IllegalStateException("Missing required environment variable: " + variable));
  }

  /**
   * Resolves a required environment variable as a non-empty string.
   *
   * @param variable the environment variable name
   * @return the trimmed value
   */
  public static String required(String variable) {
    return env(variable)
        .orElseThrow(() -> new IllegalStateException("Missing required environment variable: " + variable));
  }

  /**
   * Reads a subset of configured endpoints in one call.
   *
   * @return record containing the configured endpoints and identifiers
   */
  public static ServiceEndpoints loadServiceEndpoints() {
    return new ServiceEndpoints(
        requiredUri(ORCHESTRATOR_BASE_URL),
        requiredUri(SCENARIO_MANAGER_BASE_URL),
        required(RABBITMQ_URI),
        env(UI_WEBSOCKET_URI).map(EnvironmentConfig::toUri),
        env(SWARM_ID).orElse("pockethive-e2e"),
        env(IDEMPOTENCY_KEY_PREFIX).orElse("ph-e2e")
    );
  }

  private static Optional<String> env(String variable) {
    return Optional.ofNullable(System.getenv(variable))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  private static URI toUri(String candidate) {
    try {
      return URI.create(candidate);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Invalid URI configured: " + candidate, ex);
    }
  }

  /**
   * Immutable projection of the URLs and identifiers used across the harness.
   */
  public record ServiceEndpoints(
      URI orchestratorBaseUrl,
      URI scenarioManagerBaseUrl,
      String rabbitMqUri,
      Optional<URI> uiWebsocketUri,
      String defaultSwarmId,
      String idempotencyKeyPrefix
  ) {

    /**
     * Converts the endpoints into a simple map for logging or reporting purposes.
     * Sensitive information (such as credentials embedded in URIs) should be redacted before outputting.
     */
    public Map<String, String> asMap() {
      return Map.of(
          "orchestratorBaseUrl", orchestratorBaseUrl.toString(),
          "scenarioManagerBaseUrl", scenarioManagerBaseUrl.toString(),
          "rabbitMqUri", rabbitMqUri,
          "uiWebsocketUri", uiWebsocketUri.map(URI::toString).orElse("<not-configured>"),
          "defaultSwarmId", defaultSwarmId,
          "idempotencyKeyPrefix", idempotencyKeyPrefix
      );
    }
  }
}
