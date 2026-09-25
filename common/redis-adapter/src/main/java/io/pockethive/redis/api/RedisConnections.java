package io.pockethive.redis.api;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.pockethive.redis.config.RedisConnectionSettings;
import java.util.Objects;
/**
 * Responsibility: realize validated Redis settings once for all Redis capabilities.
 * Must not: resolve defaults, select domain policy or expose Lettuce to consumers.
 * Contract: RESP-REDIS-ADAPTER — docs/architecture/runtime-responsibilities.md#resp-redis-adapter.
 */
final class RedisConnections {
    private RedisConnections() {}
    static RedisURI uri(RedisConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        var builder = RedisURI.builder().withHost(settings.host()).withPort(settings.port()).withSsl(settings.ssl());
        if (settings.username() != null && settings.password() != null) {
            builder.withAuthentication(settings.username(), settings.password().toCharArray());
        } else if (settings.password() != null) {
            builder.withPassword(settings.password().toCharArray());
        }
        return builder.build();
    }
    static RedisClient client(RedisConnectionSettings settings) { return RedisClient.create(uri(settings)); }
}
