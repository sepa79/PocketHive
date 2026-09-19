package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose complete Redis dataset settings or their errors and deferred paths.
 * Must not: validate declarations or expose partial settings as resolved.
 * Contract: RESP-WORK-REDIS-DATASET-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset-settings.
 */
public record RedisDatasetSettingsValidation(RedisDatasetSettings settings, List<WorkConfigurationProblem> problems,
                                             List<String> deferredPaths) {
    public RedisDatasetSettingsValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        settings = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(settings, "settings") : null;
    }
}
