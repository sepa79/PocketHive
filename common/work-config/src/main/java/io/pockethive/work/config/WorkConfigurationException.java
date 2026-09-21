package io.pockethive.work.config;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Responsibility: carry canonical configuration failures and their paths to callers.
 * Must not: decide validation rules or translate errors into a transport response.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public final class WorkConfigurationException extends IllegalArgumentException {
    private final List<WorkConfigurationProblem> problems;

    public WorkConfigurationException(List<WorkConfigurationProblem> problems) {
        super(problems.stream().map(problem -> problem.path() + ": " + problem.message())
            .collect(Collectors.joining("; ")));
        this.problems = List.copyOf(problems);
    }

    public List<WorkConfigurationProblem> problems() {
        return problems;
    }
}
