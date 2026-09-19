package io.pockethive.work.config;

import java.util.List;
import java.util.Objects;

/**
 * Responsibility: return one output-adapter settings parse outcome without adapter dependencies.
 * Must not: select output types or expose settings when parsing is incomplete.
 * Contract: RESP-WORK-CONFIGURATION-PARSER — docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public record WorkOutputSettingsParseResult(
    WorkOutputSettings settings,
    List<WorkConfigurationProblem> problems,
    List<String> deferredPaths
) {
    public WorkOutputSettingsParseResult {
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        deferredPaths = List.copyOf(Objects.requireNonNull(deferredPaths, "deferredPaths"));
        validateOutcome(settings, problems, deferredPaths);
    }

    private static void validateOutcome(WorkOutputSettings settings, List<WorkConfigurationProblem> problems,
                                        List<String> deferredPaths) {
        if (problems.isEmpty() && deferredPaths.isEmpty()) {
            Objects.requireNonNull(settings, "settings");
        } else if (settings != null) {
            throw new IllegalArgumentException("Incomplete parse must not expose output settings.");
        }
    }
}
