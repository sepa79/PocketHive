package io.pockethive.work.config.input;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose a validated schedule integer or its errors/deferred paths.
 * Must not: decide constraints or expose an invalid/deferred value as accepted settings.
 * Contract: RESP-WORK-INPUT-SCHEDULE — docs/architecture/runtime-responsibilities.md#resp-work-input-schedule.
 */
public record InputScheduleValidation(Long value, List<WorkConfigurationProblem> problems,
                                      List<String> deferredPaths) {
    public InputScheduleValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        value = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(value, "value") : null;
    }
}
