package io.pockethive.work.config;

import java.util.List;

/**
 * Responsibility: expose canonical Redis destination settings or their errors/deferred constraints.
 * Must not: validate declarations, render templates or expose partial settings as resolved.
 * Contract: RESP-WORK-REDIS-TARGETS — docs/architecture/runtime-responsibilities.md#resp-work-redis-targets.
 */
public record RedisOutputTargetsValidation(List<RedisRoute> routes, String defaultList, String targetListTemplate,
                                           List<WorkConfigurationProblem> problems, List<String> deferredPaths) {
    public RedisOutputTargetsValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        routes = List.copyOf(routes);
        if (!problems.isEmpty() || !deferredPaths.isEmpty()) {
            routes = List.of();
            defaultList = null;
            targetListTemplate = null;
        }
    }
}
