package io.pockethive.work.config.redis;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose the validated source choice or its canonical errors/deferred constraints.
 * Must not: decide source-mode rules or expose a partial selection as valid.
 * Contract: RESP-WORK-REDIS-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-redis-selection.
 */
public record RedisDatasetSelectionValidation(RedisDatasetSourceMode mode, String listName,
                                              List<RedisDatasetSource> sources,
                                              List<WorkConfigurationProblem> problems, List<String> deferredPaths) {
    public RedisDatasetSelectionValidation {
        mode = Objects.requireNonNull(mode, "mode");
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        sources = List.copyOf(sources);
        if (!problems.isEmpty() || !deferredPaths.isEmpty()) {
            mode = RedisDatasetSourceMode.UNRESOLVED;
            listName = null;
            sources = List.of();
        }
    }
}
