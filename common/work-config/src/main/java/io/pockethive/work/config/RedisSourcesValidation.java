package io.pockethive.work.config;

import java.util.List;

/**
 * Responsibility: expose source-list validation without leaking partially validated sources.
 * Must not: validate declarations or represent deferred/invalid sources as known empty configuration.
 * Contract: RESP-WORK-REDIS-SOURCES — docs/architecture/runtime-responsibilities.md#resp-work-redis-sources.
 */
public record RedisSourcesValidation(List<RedisDatasetSource> sources, List<WorkConfigurationProblem> problems,
                                     List<String> deferredPaths) {
    public RedisSourcesValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        sources = problems.isEmpty() && deferredPaths.isEmpty() ? List.copyOf(sources) : List.of();
    }

    public boolean isEmpty() {
        return sources.isEmpty() && problems.isEmpty() && deferredPaths.isEmpty();
    }
}
