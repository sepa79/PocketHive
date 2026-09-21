package io.pockethive.redis.config;

import io.pockethive.work.config.WorkOutputSettings;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: retain one complete resolved Redis output configuration.
 * Must not: parse declarations, render destinations or access Redis.
 * Contract: RESP-WORK-REDIS-OUTPUT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-output-settings.
 */
public record RedisOutputSettings(RedisConnectionSettings connection, RedisWriteSettings writeSettings,
                                  List<RedisRoute> routes, String defaultList, String targetListTemplate)
    implements WorkOutputSettings {
    public RedisOutputSettings {
        connection = Objects.requireNonNull(connection, "connection");
        writeSettings = Objects.requireNonNull(writeSettings, "writeSettings");
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
    }
}
