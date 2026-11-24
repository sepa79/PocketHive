package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationTemplate;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project the Redis target-list template consumed by per-message routing.
 * Must not: select adapters, parse connection settings or render a destination.
 * Contract: RESP-WORK-REDIS-TARGETS — docs/architecture/runtime-responsibilities.md#resp-work-redis-targets.
 */
public final class RedisOutputTemplateSources {
    public static final String FIELD = "targetListTemplate";
    private RedisOutputTemplateSources() {}

    public static Object value(Map<?, ?> settings) { return settings.get(FIELD); }

    public static List<WorkConfigurationTemplate> sources(Map<?, ?> settings, String path) {
        return value(settings) instanceof String source
            ? List.of(new WorkConfigurationTemplate(path + "." + FIELD, source)) : List.of();
    }
}
