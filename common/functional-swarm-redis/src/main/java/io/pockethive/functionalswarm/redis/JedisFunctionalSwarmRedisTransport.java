package io.pockethive.functionalswarm.redis;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.util.KeyValue;

/** Jedis implementation of the Functional Swarm Redis transport. */
public final class JedisFunctionalSwarmRedisTransport implements FunctionalSwarmRedisTransport {
  private static final String WRITE_REPLY_SCRIPT = """
      if redis.call('SET', KEYS[2], ARGV[1], 'NX', 'EX', ARGV[3]) then
        redis.call('RPUSH', KEYS[1], ARGV[2])
        redis.call('EXPIRE', KEYS[1], ARGV[3])
        return 1
      end
      return 0
      """;

  @Override
  public void publishRequest(FunctionalSwarmRedisEndpoint endpoint, String requestList, String requestPayload) {
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    try (Jedis jedis = open(endpoint, endpoint.operationTimeoutMs())) {
      jedis.rpush(requireText(requestList, "requestList"), requireText(requestPayload, "requestPayload"));
    }
  }

  @Override
  public Optional<String> awaitReply(FunctionalSwarmRedisEndpoint endpoint, String replyList, Duration timeout) {
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    String list = requireText(replyList, "replyList");
    if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1) {
      throw new IllegalArgumentException("timeout must be at least one millisecond");
    }
    int blockingTimeoutMillis = Math.toIntExact(timeout.toMillis());
    try (Jedis jedis = open(endpoint, blockingTimeoutMillis)) {
      KeyValue<String, String> reply = jedis.blpop(timeout.toMillis() / 1000.0d, list);
      return reply == null ? Optional.empty() : Optional.ofNullable(reply.getValue());
    }
  }

  @Override
  public boolean publishReplyOnce(
      FunctionalSwarmRedisEndpoint endpoint,
      String replyList,
      String requestId,
      String responsePayload,
      long replyTtlSeconds
  ) {
    endpoint = Objects.requireNonNull(endpoint, "endpoint");
    if (replyTtlSeconds < 1) {
      throw new IllegalArgumentException("replyTtlSeconds must be positive");
    }
    String list = requireText(replyList, "replyList");
    try (Jedis jedis = open(endpoint, endpoint.operationTimeoutMs())) {
      Object outcome = jedis.eval(
          WRITE_REPLY_SCRIPT,
          List.of(list, list + ".sent"),
          List.of(
              requireText(requestId, "requestId"),
              Objects.requireNonNull(responsePayload, "responsePayload"),
              Long.toString(replyTtlSeconds)));
      return Long.valueOf(1L).equals(outcome);
    }
  }

  private static Jedis open(FunctionalSwarmRedisEndpoint endpoint, int blockingTimeoutMillis) {
    DefaultJedisClientConfig config = DefaultJedisClientConfig.builder()
        .connectionTimeoutMillis(endpoint.operationTimeoutMs())
        .socketTimeoutMillis(endpoint.operationTimeoutMs())
        .blockingSocketTimeoutMillis(blockingTimeoutMillis)
        .ssl(endpoint.ssl())
        .build();
    return new Jedis(endpoint.host(), endpoint.port(), config);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
