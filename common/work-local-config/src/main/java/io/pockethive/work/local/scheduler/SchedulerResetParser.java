package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Responsibility: validate declared scheduler reset flags through one boolean contract.
 * Must not: coerce text, reset counters or accept unresolved runtime expressions.
 * Contract: RESP-WORK-SCHEDULER-RESET — docs/architecture/runtime-responsibilities.md#resp-work-scheduler-reset.
 */
public final class SchedulerResetParser {
    public static final String FIELD = "reset";
    public static final String PATH = "inputs.scheduler.reset";

    public boolean parse(Object value, String path) {
        var result = validate(value, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.resetRequested();
    }

    public SchedulerResetValidation validate(Object value, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (WorkConfigurationExpressions.symbolic(value, path, mode, problems, deferred)) {
            return new SchedulerResetValidation(null, problems, deferred);
        }
        Boolean reset = value instanceof Boolean flag ? flag : null;
        if (reset == null) {
            problems.add(new WorkConfigurationProblem(path, "reset must be boolean (true or false)."));
        }
        return new SchedulerResetValidation(reset, problems, deferred);
    }
}
