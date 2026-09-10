package io.pockethive.work.config.input;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose a validated reset flag or its errors/deferred paths.
 * Must not: decide reset semantics or expose an invalid/deferred flag as accepted.
 * Contract: RESP-WORK-SCHEDULER-RESET — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-reset.
 */
public record SchedulerResetValidation(Boolean resetRequested, List<WorkConfigurationProblem> problems,
                                       List<String> deferredPaths) {
    public SchedulerResetValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        resetRequested = problems.isEmpty() && deferredPaths.isEmpty()
            ? Objects.requireNonNull(resetRequested, "resetRequested") : null;
    }
}
