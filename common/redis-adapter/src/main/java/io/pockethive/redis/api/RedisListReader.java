package io.pockethive.redis.api;
/**
 * Responsibility: provide owned Redis list operations and explicit resource release.
 * Must not: resolve defaults, select domain policy or expose Lettuce to consumers.
 * Contract: RESP-REDIS-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-redis-adapter.
 */
public interface RedisListReader extends AutoCloseable {
    String pop(String listName);
    @Override void close();
}
