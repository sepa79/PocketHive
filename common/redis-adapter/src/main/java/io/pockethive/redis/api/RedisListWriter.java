package io.pockethive.redis.api;
/**
 * Responsibility: provide owned Redis list operations and explicit resource release.
 * Must not: resolve defaults, select domain policy or expose Lettuce to consumers.
 * Contract: RESP-REDIS-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-redis-adapter.
 */
public interface RedisListWriter extends AutoCloseable {
    void push(String list, String payload, io.pockethive.redis.config.RedisPushDirection direction, int maxLen);
    @Override void close();
}
