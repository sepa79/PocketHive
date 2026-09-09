package io.pockethive.work.config;

import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose resolved write settings or their canonical errors/deferred paths.
 * Must not: validate declarations or expose a partial configuration as resolved.
 * Contract: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 */
public record RedisWriteSettingsValidation(RedisWriteSettings settings, List<WorkConfigurationProblem> problems,
                                           List<String> deferredPaths) {
    public RedisWriteSettingsValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        settings = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(settings, "settings") : null;
    }
}
