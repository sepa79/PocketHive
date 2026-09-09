package io.pockethive.worker.sdk.config;

import io.pockethive.templating.RedisSequenceGenerator;
import io.pockethive.work.config.redis.RedisConfigurationParser;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: apply validated worker Redis connection settings to the existing sequence owner.
 * Must not: decode/clamp connection values or silently ignore invalid updates.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 * Global sequence ownership remains RESP-TEMPLATE-SEQUENCE pending B06.
 */
@Configuration
@EnableConfigurationProperties(RedisSequenceProperties.class)
@ConditionalOnProperty(prefix = RedisSequenceProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisSequenceConfiguration {
    RedisSequenceConfiguration(RedisSequenceProperties properties) {
        if (properties.isEnabled()) {
            RedisSequenceGenerator.configure(properties.connectionSettings(RedisSequenceProperties.PREFIX));
        }
    }

    public static void configureFromWorkerConfig(Map<String, Object> config) {
        if (config == null || !config.containsKey("redis")) return;
        if (!(config.get("redis") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("redis must be an object");
        }
        var settings = new RedisConfigurationParser().mergeRedisConnection(
            RedisSequenceGenerator.currentConfig(), values, "redis");
        RedisSequenceGenerator.configure(settings);
    }
}
