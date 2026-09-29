package io.pockethive.redis.api;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.pockethive.redis.config.RedisConnectionSettings;
import io.pockethive.redis.config.RedisPushDirection;
import java.time.Duration;
/**
 * Responsibility: execute existing list commands using one owned connection with a ten-second timeout.
 * Must not: resolve defaults, select domain policy or expose Lettuce to consumers.
 * Contract: RESP-REDIS-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-redis-adapter.
 */
final class RedisListConnection implements RedisListReader, RedisListWriter {
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    RedisListConnection(RedisConnectionSettings settings) {
        this(settings, RedisClient::create);
    }
    RedisListConnection(RedisConnectionSettings settings, java.util.function.Function<io.lettuce.core.RedisURI, RedisClient> factory) {
        client = factory.apply(RedisConnections.uri(settings));
        connection = client.connect();
        connection.setTimeout(Duration.ofSeconds(10));
    }
    public String pop(String listName) { return connection.sync().lpop(listName); }
    public void push(String list, String payload, RedisPushDirection direction, int maxLen) {
        var commands = connection.sync();
        if (direction == RedisPushDirection.LPUSH) commands.lpush(list, payload);
        else commands.rpush(list, payload);
        if (maxLen > 0) commands.ltrim(list, 0, maxLen - 1);
    }
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try { connection.close(); } finally { client.shutdown(); }
        }
    }
}
