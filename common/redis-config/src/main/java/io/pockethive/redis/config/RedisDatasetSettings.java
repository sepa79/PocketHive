package io.pockethive.redis.config;

import io.pockethive.work.config.WorkInputSettings;

import java.util.List;
import java.util.Objects;

/**
 * Responsibility: retain the complete resolved Redis dataset input settings.
 * Must not: parse declarations, select a source or access Redis.
 * Contract: RESP-WORK-REDIS-DATASET-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset-settings.
 */
public record RedisDatasetSettings(RedisConnectionSettings connection, RedisDatasetSourceMode sourceMode,
                                   String listName, List<RedisDatasetSource> sources,
                                   RedisDatasetPickStrategy pickStrategy, double ratePerSec,
                                   long initialDelayMs, long tickIntervalMs) implements WorkInputSettings {
    public RedisDatasetSettings {
        connection = Objects.requireNonNull(connection, "connection");
        sourceMode = Objects.requireNonNull(sourceMode, "sourceMode");
        sources = List.copyOf(sources);
        pickStrategy = Objects.requireNonNull(pickStrategy, "pickStrategy");
    }
}
