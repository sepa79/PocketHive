package io.pockethive.redis.config;

import io.pockethive.work.config.WorkInputSettingsParseResult;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.Map;

/**
 * Responsibility: adapt canonical Redis dataset parsing to the neutral input settings parser port.
 * Must not: select inputs, retain accepted state or access Redis.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public final class RedisWorkInputSettingsParser implements WorkInputSettingsParser {
    private final RedisConfigurationParser parser;

    public RedisWorkInputSettingsParser(RedisConfigurationParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerInputType type() {
        return WorkerInputType.REDIS_DATASET;
    }

    @Override
    public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        var result = parser.validateRedisDatasetSettings(settings, path, mode);
        return new WorkInputSettingsParseResult(result.settings(), result.problems(), result.deferredPaths());
    }
}
