package io.pockethive.work.config;

import java.util.List;

/**
 * Responsibility: mark symbolic Work fields deferred in authoring or invalid when resolved.
 * Must not: parse template syntax, render expressions or validate concrete field values.
 * Contract: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate;
 * RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public final class WorkConfigurationExpressions {
    private WorkConfigurationExpressions() { }

    public static boolean symbolic(Object value, String path, WorkConfigurationMode mode,
                                   List<WorkConfigurationProblem> problems, List<String> deferred) {
        if (!(value instanceof String text)
            || !((text.contains("{{") && text.contains("}}")) || (text.contains("{%") && text.contains("%}")))) {
            return false;
        }
        if (mode == WorkConfigurationMode.AUTHORING) {
            deferred.add(path);
        } else {
            problems.add(new WorkConfigurationProblem(path, "Configuration expression must be rendered before runtime parsing."));
        }
        return true;
    }
}
