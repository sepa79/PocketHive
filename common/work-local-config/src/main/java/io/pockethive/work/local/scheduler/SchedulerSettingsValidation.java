package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose complete scheduler settings or errors/deferred constraints.
 * Must not: validate values or expose partially validated settings.
 * Contract: RESP-WORK-SCHEDULER-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-settings.
 */
public record SchedulerSettingsValidation(SchedulerSettings settings, List<WorkConfigurationProblem> problems,
                                          List<String> deferredPaths) {
    public SchedulerSettingsValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        settings = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(settings) : null;
    }
}
