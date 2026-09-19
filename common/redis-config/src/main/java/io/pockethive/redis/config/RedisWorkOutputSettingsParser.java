package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkOutputSettingsParseResult;
import io.pockethive.work.config.WorkOutputSettingsParser;
import io.pockethive.work.config.WorkerOutputType;
import java.util.Map;

/**
 * Responsibility: adapt canonical Redis output parsing to the neutral output settings parser port.
 * Must not: select outputs, retain accepted state or access Redis.
 * Contract: RESP-WORK-REDIS-OUTPUT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-output-settings.
 */
public final class RedisWorkOutputSettingsParser implements WorkOutputSettingsParser {
    private final RedisConfigurationParser parser;

    public RedisWorkOutputSettingsParser(RedisConfigurationParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerOutputType type() {
        return WorkerOutputType.REDIS;
    }

    @Override
    public WorkOutputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        var result = parser.validateRedisOutputSettings(settings, path, mode);
        return new WorkOutputSettingsParseResult(result.settings(), result.problems(), result.deferredPaths());
    }
}
