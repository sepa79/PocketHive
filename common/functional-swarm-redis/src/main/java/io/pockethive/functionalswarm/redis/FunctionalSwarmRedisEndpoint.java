package io.pockethive.functionalswarm.redis;

/** Explicit Redis endpoint settings shared by Functional Swarm clients and reply workers. */
public record FunctionalSwarmRedisEndpoint(String host, int port, boolean ssl, int operationTimeoutMs) {
  public FunctionalSwarmRedisEndpoint {
    host = requireText(host, "host");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    if (operationTimeoutMs < 1) {
      throw new IllegalArgumentException("operationTimeoutMs must be positive");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
