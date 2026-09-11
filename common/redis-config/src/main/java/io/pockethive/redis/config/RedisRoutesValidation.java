package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;

/**
 * Responsibility: expose canonical route validation, deferred paths and a completely validated route list.
 * Must not: revalidate declarations or represent unresolved/invalid routes as known empty configuration.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public record RedisRoutesValidation(List<RedisRoute> routes, List<WorkConfigurationProblem> problems,
                                    List<String> deferredPaths) {
    public RedisRoutesValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        routes = problems.isEmpty() && deferredPaths.isEmpty() ? List.copyOf(routes) : List.of();
    }

    public boolean isEmpty() {
        return routes.isEmpty() && problems.isEmpty() && deferredPaths.isEmpty();
    }
}
