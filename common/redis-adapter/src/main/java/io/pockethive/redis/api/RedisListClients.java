package io.pockethive.redis.api;
import io.pockethive.redis.config.RedisConnectionSettings;
/**
 * Responsibility: open explicit list capabilities without exposing the client implementation.
 * Must not: resolve defaults, select domain policy or expose Lettuce to consumers.
 * Contract: RESP-REDIS-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-redis-adapter.
 */
public final class RedisListClients {
    private RedisListClients() {}
    public static RedisListReader reader(RedisConnectionSettings settings) { return new RedisListConnection(settings); }
    public static RedisListWriter writer(RedisConnectionSettings settings) { return new RedisListConnection(settings); }
}
