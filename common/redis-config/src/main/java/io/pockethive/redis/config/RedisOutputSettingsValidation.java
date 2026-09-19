package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose a complete Redis output configuration or canonical validation findings.
 * Must not: expose partial output settings or apply Redis configuration.
 * Contract: RESP-WORK-REDIS-OUTPUT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-output-settings.
 */
public record RedisOutputSettingsValidation(RedisOutputSettings settings, List<WorkConfigurationProblem> problems,
                                            List<String> deferredPaths) {
    public RedisOutputSettingsValidation {
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        deferredPaths = List.copyOf(Objects.requireNonNull(deferredPaths, "deferredPaths"));
        if (problems.isEmpty() && deferredPaths.isEmpty()) {
            settings = Objects.requireNonNull(settings, "settings");
        } else if (settings != null) {
            throw new IllegalArgumentException("Incomplete Redis output validation must not expose settings.");
        }
    }
}
