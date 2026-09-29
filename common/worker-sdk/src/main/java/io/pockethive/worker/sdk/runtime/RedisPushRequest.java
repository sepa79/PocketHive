package io.pockethive.worker.sdk.runtime;
import io.pockethive.redis.config.RedisConnectionSettings;
import io.pockethive.redis.config.RedisWriteSettings;
import io.pockethive.redis.config.RedisRoute;
import java.util.List;
import java.util.Objects;
/**
 * Responsibility: carry already validated push settings and route selection inputs.
 * Must not: open connections or parse settings.
 * Contract: RESP-WORK-REDIS-PUSH — docs/architecture/runtime-responsibilities.md#resp-work-redis-push.
 */
public record RedisPushRequest(RedisConnectionSettings connection, RedisWriteSettings settings,
    List<RedisRoute> routes, String defaultList, String targetListTemplate) {
    public RedisPushRequest {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(settings, "settings");
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
