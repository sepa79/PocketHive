package io.pockethive.functionalswarmreply;

import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisEndpoint;

/** Explicit Redis sink settings for Functional Swarm RPC replies. */
public record FunctionalSwarmReplyWorkerConfig(
    FunctionalSwarmRedisEndpoint redis,
    String replyListPrefix,
    long replyTtlSeconds
) {
  public FunctionalSwarmReplyWorkerConfig {
    if (redis == null) {
      throw new IllegalArgumentException("redis must not be null");
    }
    replyListPrefix = requireText(replyListPrefix, "replyListPrefix");
    if (replyTtlSeconds < 1) {
      throw new IllegalArgumentException("replyTtlSeconds must be positive");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
