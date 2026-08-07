package io.pockethive.functionalswarmdsl;

import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisEndpoint;
import java.time.Duration;

/** Explicit Redis ingress settings for one remote Functional Swarm. */
public record FunctionalSwarmRemoteConfig(
    FunctionalSwarmRedisEndpoint redis,
    String requestList,
    String replyListPrefix,
    Duration timeout
) {
  public FunctionalSwarmRemoteConfig {
    if (redis == null) {
      throw new IllegalArgumentException("redis must not be null");
    }
    requestList = requireText(requestList, "requestList");
    replyListPrefix = requireText(replyListPrefix, "replyListPrefix");
    if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.toMillis() < 1) {
      throw new IllegalArgumentException("timeout must be at least one millisecond");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
