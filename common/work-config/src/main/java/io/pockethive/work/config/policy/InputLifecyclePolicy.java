package io.pockethive.work.config.policy;

import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerInputType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Responsibility: reject input-local lifecycle controls in configuration and startup properties.
 * Must not: read process environment, parse boolean values or change worker enablement.
 * Contract: RESP-WORK-INPUT-LIFECYCLE-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-input-lifecycle-policy.
 */
public final class InputLifecyclePolicy {
    private static final String ENABLED = "enabled";
    private static final Map<String, String> INPUT_FIELDS = Map.of(ENABLED, ENABLED);
    private static final Map<String, String> RABBIT_FIELDS = Map.of(ENABLED, ENABLED, "autoStartup", "auto-startup");
    private static final String MESSAGE = "Input-local lifecycle controls are unsupported; use worker control enablement.";

    public List<WorkConfigurationProblem> configurationProblems(Object inputs, String path) {
        Objects.requireNonNull(path, "path");
        if (!(inputs instanceof Map<?, ?> inputMap)) return List.of();
        var problems = new ArrayList<WorkConfigurationProblem>();
        for (var type : WorkerInputType.values()) {
            if (inputMap.get(type.settingsKey()) instanceof Map<?, ?> settings) {
                for (String field : fields(type).keySet()) {
                    if (settings.containsKey(field)) {
                        problems.add(new WorkConfigurationProblem(path + "." + type.settingsKey() + "." + field, MESSAGE));
                    }
                }
            }
        }
        return List.copyOf(problems);
    }

    public List<WorkConfigurationProblem> propertyProblems(Predicate<String> isPresent) {
        Objects.requireNonNull(isPresent, "isPresent");
        var problems = new ArrayList<WorkConfigurationProblem>();
        for (var type : WorkerInputType.values()) {
            for (String field : fields(type).values()) {
                String path = "pockethive.inputs." + type.settingsKey() + "." + field;
                if (isPresent.test(path)) {
                    problems.add(new WorkConfigurationProblem(path, MESSAGE));
                }
            }
        }
        return List.copyOf(problems);
    }

    private static Map<String, String> fields(WorkerInputType type) {
        return type == WorkerInputType.RABBITMQ ? RABBIT_FIELDS : INPUT_FIELDS;
    }
}
