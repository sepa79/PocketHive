package io.pockethive.work.config.input;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose a resolved input rate or its errors/deferred paths.
 * Must not: validate rates or expose an invalid/deferred value as accepted settings.
 * Contract: RESP-WORK-INPUT-RATE — docs/architecture/runtime-responsibilities.md#resp-work-input-rate.
 */
public record InputRateValidation(Double ratePerSec, List<WorkConfigurationProblem> problems,
                                  List<String> deferredPaths) {
    public InputRateValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        ratePerSec = problems.isEmpty() && deferredPaths.isEmpty()
            ? Objects.requireNonNull(ratePerSec, "ratePerSec") : null;
    }
}
