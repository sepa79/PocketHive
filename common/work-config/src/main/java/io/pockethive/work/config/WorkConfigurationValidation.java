package io.pockethive.work.config;

import java.util.List;
import java.util.Objects;

/**
 * Responsibility: carry a neutral selected-block validation outcome.
 * Must not: contain adapter-specific settings values or decide configuration selection.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public record WorkConfigurationValidation(
    CompleteWorkConfiguration configuration,
    List<WorkConfigurationProblem> problems,
    List<String> deferredPaths
) {
    public WorkConfigurationValidation {
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        deferredPaths = List.copyOf(Objects.requireNonNull(deferredPaths, "deferredPaths"));
        if (problems.isEmpty() && deferredPaths.isEmpty()) {
            Objects.requireNonNull(configuration, "configuration");
        } else if (configuration != null) {
            throw new IllegalArgumentException("Incomplete validation must not expose configuration.");
        }
    }
}
