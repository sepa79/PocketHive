package io.pockethive.e2e.config;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Centralises access to the environment variables that drive the PocketHive end-to-end harness.
 */
public final class EnvironmentConfig {

  public static final String ORCHESTRATOR_BASE_URL = "ORCHESTRATOR_BASE_URL";
  public static final String SCENARIO_MANAGER_BASE_URL = "SCENARIO_MANAGER_BASE_URL";
  public static final String RABBITMQ_HOST = "RABBITMQ_HOST";
  public static final String RABBITMQ_PORT = "RABBITMQ_PORT";
  public static final String RABBITMQ_DEFAULT_USER = "RABBITMQ_DEFAULT_USER";
  public static final String RABBITMQ_DEFAULT_PASS = "RABBITMQ_DEFAULT_PASS";
  public static final String RABBITMQ_VHOST = "RABBITMQ_VHOST";
  public static final String RABBITMQ_MANAGEMENT_BASE_URL = "RABBITMQ_MANAGEMENT_BASE_URL";
  public static final String RABBITMQ_MANAGEMENT_PORT = "RABBITMQ_MANAGEMENT_PORT";
  public static final String UI_WEBSOCKET_URI = "UI_WEBSOCKET_URI";
  public static final String UI_BASE_URL = "UI_BASE_URL";
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
        loadRabbitMqSettings(),
        env(UI_WEBSOCKET_URI).map(EnvironmentConfig::toUri),
        resolveUiBaseUrl(),
        env(SWARM_ID).orElse("pockethive-e2e"),
        env(IDEMPOTENCY_KEY_PREFIX).orElse("ph-e2e")
    );
  }

  private static RabbitMqSettings loadRabbitMqSettings() {
    String host = env(RABBITMQ_HOST).orElse("rabbitmq");
    int port = env(RABBITMQ_PORT)
        .map(value -> {
          try {
            return Integer.parseInt(value);
          } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid RabbitMQ port configured: " + value, ex);
          }
        })
        .orElse(5672);
    String username = env(RABBITMQ_DEFAULT_USER).orElse("guest");
    String password = env(RABBITMQ_DEFAULT_PASS).orElse("guest");
    String virtualHost = env(RABBITMQ_VHOST).orElse("/");
    int managementPort = env(RABBITMQ_MANAGEMENT_PORT)
        .map(value -> parsePort(value, "RabbitMQ management port"))
        .orElse(15672);
    URI managementBaseUrl = env(RABBITMQ_MANAGEMENT_BASE_URL)
        .map(EnvironmentConfig::toUri)
        .orElseGet(() -> httpUri(host, managementPort, "/api"));
    return new RabbitMqSettings(host, port, username, password, virtualHost, managementBaseUrl);
  }

  private static int parsePort(String value, String field) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid " + field + " configured: " + value, ex);
    }
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
      RabbitMqSettings rabbitMq,
      Optional<URI> uiWebsocketUri,
      Optional<URI> uiBaseUrl,
      String defaultSwarmId,
      String idempotencyKeyPrefix
  ) {

    /**
     * Converts the endpoints into a simple map for logging or reporting purposes.
     * Sensitive information (such as credentials embedded in URIs) should be redacted before outputting.
     */
    public Map<String, String> asMap() {
      return Map.ofEntries(
          Map.entry("orchestratorBaseUrl", orchestratorBaseUrl.toString()),
          Map.entry("scenarioManagerBaseUrl", scenarioManagerBaseUrl.toString()),
          Map.entry("rabbitMqHost", rabbitMq.host()),
          Map.entry("rabbitMqPort", Integer.toString(rabbitMq.port())),
          Map.entry("rabbitMqUsername", rabbitMq.username()),
          Map.entry("rabbitMqVirtualHost", rabbitMq.virtualHost()),
          Map.entry("rabbitMqManagementBaseUrl", rabbitMq.managementBaseUrl().toString()),
          Map.entry("uiWebsocketUri", uiWebsocketUri.map(URI::toString).orElse("<not-configured>")),
          Map.entry("uiBaseUrl", uiBaseUrl.map(URI::toString).orElse("<not-configured>")),
          Map.entry("defaultSwarmId", defaultSwarmId),
          Map.entry("idempotencyKeyPrefix", idempotencyKeyPrefix)
      );
    }
  }

  public record RabbitMqSettings(String host,
                                 int port,
                                 String username,
                                 String password,
                                 String virtualHost,
                                 URI managementBaseUrl) {

    public RabbitMqSettings {
      host = requireNonBlank(host, "RabbitMQ host");
      username = requireNonBlank(username, "RabbitMQ username");
      password = password == null ? "" : password;
      virtualHost = normaliseVirtualHost(virtualHost);
      managementBaseUrl = Objects.requireNonNull(managementBaseUrl, "RabbitMQ management base URL must not be null");
    }

    public String redactedUri() {
      String safeUser = username.isBlank() ? "<anonymous>" : username;
      String vhostSegment = "/".equals(virtualHost) ? "/" : ensureLeadingSlash(virtualHost);
      return String.format("amqp://%s:***@%s:%d%s", safeUser, host, port, vhostSegment);
    }

    private static String normaliseVirtualHost(String value) {
      if (value == null || value.isBlank()) {
        return "/";
      }
      return value;
    }

    private static String requireNonBlank(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(field + " must not be blank");
      }
      return value;
    }

    private static String ensureLeadingSlash(String value) {
      if (value.startsWith("/")) {
        return value;
      }
      return "/" + value;
    }
  }

  private static Optional<URI> resolveUiBaseUrl() {
    Optional<URI> explicit = env(UI_BASE_URL).map(EnvironmentConfig::toUri);
    if (explicit.isPresent()) {
      return explicit;
    }

    return env(UI_WEBSOCKET_URI)
        .map(EnvironmentConfig::toUri)
        .map(EnvironmentConfig::deriveHttpBaseFromWebsocket);
  }

  private static URI deriveHttpBaseFromWebsocket(URI websocketUri) {
    String scheme = websocketUri.getScheme();
    String httpScheme;
    if ("ws".equalsIgnoreCase(scheme)) {
      httpScheme = "http";
    } else if ("wss".equalsIgnoreCase(scheme)) {
      httpScheme = "https";
    } else {
      throw new IllegalStateException("Unsupported WebSocket scheme for UI proxy: " + scheme);
    }

    try {
      return new URI(httpScheme, websocketUri.getUserInfo(), websocketUri.getHost(), websocketUri.getPort(), null, null, null);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to derive HTTP base URL from WebSocket URI: " + websocketUri, ex);
    }
  }

  private static URI httpUri(String host, int port, String path) {
    try {
      return new URI("http", null, host, port, path, null, null);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to construct RabbitMQ management URI", ex);
    }
  }
}
