package io.pockethive.work.config.policy;

import io.pockethive.work.config.WorkConfigurationFields;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.function.Function;

/**
 * Responsibility: reject competing worker IO selections in supplied environment properties.
 * Must not: infer adapter types, read process environment or validate adapter settings.
 * Contract: RESP-CONTROLLER-WORK-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-controller-work-configuration.
 */
public final class WorkSelectorEnvironmentPolicy {
    public List<WorkConfigurationProblem> problems(Function<String, String> properties) {
        return List.of(WorkConfigurationFields.INPUTS, WorkConfigurationFields.OUTPUTS).stream()
            .map(root -> "pockethive." + root + "." + WorkConfigurationFields.TYPE)
            .filter(path -> properties.apply(path) != null)
            .map(path -> new WorkConfigurationProblem(path,
                "Work IO selection belongs in config; environment selector overrides are unsupported."))
            .toList();
    }
}
