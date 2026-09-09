package io.pockethive.work.config.redis;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose resolved Redis connection settings or their errors/deferred paths.
 * Must not: validate declarations or expose partially validated settings.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
public record RedisConnectionValidation(RedisConnectionSettings settings, List<WorkConfigurationProblem> problems,
                                         List<String> deferredPaths) {
    public RedisConnectionValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        settings = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(settings, "settings") : null;
    }
}
