package io.pockethive.artemis.config;

import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Responsibility: map Artemis boundary field failures and expressions to neutral parse diagnostics.
 * Must not: define scalar rules, resolve topology or retain accepted configuration.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
final class ArtemisSettingsParsing {
    private ArtemisSettingsParsing() { }

    static void fields(Map<?, ?> settings, Set<String> allowed, String path,
                       List<WorkConfigurationProblem> problems) {
        settings.keySet().stream().filter(key -> !allowed.contains(key)).forEach(key ->
            problems.add(new WorkConfigurationProblem(path + "." + key, "Unsupported Artemis setting.")));
    }

    static <T> T value(Object raw, String path, WorkConfigurationMode mode,
                       List<WorkConfigurationProblem> problems, List<String> deferred, Function<Object, T> parse) {
        if (WorkConfigurationExpressions.symbolic(raw, path, mode, problems, deferred)) return null;
        try {
            return parse.apply(raw);
        } catch (IllegalArgumentException invalid) {
            problems.add(new WorkConfigurationProblem(path, invalid.getMessage()));
            return null;
        }
    }
}
