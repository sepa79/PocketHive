package io.pockethive.redis.config;

import io.pockethive.work.config.InvocationInterceptorConfiguration;
import java.util.Map;

/**
 * Responsibility: locate the Redis uploader settings and decode their concrete enablement.
 * Must not: render deferred expressions, discover templates or perform Redis writes.
 * Contract: RESP-WORK-MESSAGE-TEMPLATE — docs/architecture/runtime-responsibilities.md#resp-work-message-template.
 */
public final class RedisUploaderConfiguration {
    private static final String NAME = "redisUploader";
    public static final String PATH = InvocationInterceptorConfiguration.path(NAME);
    public static final String ENABLED_FIELD = "enabled";
    public static final String ENABLED_PATH = PATH + "." + ENABLED_FIELD;

    private RedisUploaderConfiguration() {}

    public static Map<?, ?> block(Map<String, Object> configuration) {
        return InvocationInterceptorConfiguration.block(configuration, NAME);
    }

    public static boolean enabled(Map<?, ?> settings) {
        if (!settings.containsKey(ENABLED_FIELD)) {
            return false;
        }
        Object value = settings.get(ENABLED_FIELD);
        if (value == null) {
            throw new IllegalStateException("Redis uploader config requires field 'enabled' when enabled.");
        }
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
        }
        throw new IllegalStateException("Redis uploader config field 'enabled' must be true or false.");
    }
}
