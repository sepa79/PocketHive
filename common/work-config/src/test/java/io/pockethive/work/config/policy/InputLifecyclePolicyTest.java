package io.pockethive.work.config.policy;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputLifecyclePolicyTest {
    private final InputLifecyclePolicy policy = new InputLifecyclePolicy();

    @Test
    void rejectsRemovedControlsByPresenceEvenWhenNullFalseOrSymbolic() {
        for (String type : List.of("rabbit", "scheduler", "redis", "csv")) {
            for (Object value : new Object[]{null, false, true, "{{ false }}", Map.of(), List.of()}) {
                var fields = new LinkedHashMap<String, Object>();
                fields.put("enabled", value);
                assertThat(policy.configurationProblems(Map.of(type, fields), "inputs"))
                    .extracting(WorkConfigurationProblem::path).containsExactly("inputs." + type + ".enabled");
            }
        }
        assertThat(policy.configurationProblems(Map.of("rabbit", Map.of("autoStartup", false)), "inputs"))
            .extracting(WorkConfigurationProblem::path).containsExactly("inputs.rabbit.autoStartup");
        assertThat(policy.configurationProblems(Map.of("csv", Map.of("rotate", false)), "inputs")).isEmpty();
    }

    @Test
    void rejectsStartupPropertiesWithoutInterpretingTheirValues() {
        var properties = Map.of("pockethive.inputs.redis.enabled", "",
            "pockethive.inputs.rabbit.auto-startup", "false");
        assertThat(policy.propertyProblems(properties::containsKey)).extracting(WorkConfigurationProblem::path)
            .containsExactlyInAnyOrder("pockethive.inputs.redis.enabled", "pockethive.inputs.rabbit.auto-startup");
        assertThat(policy.propertyProblems(Map.of("pockethive.worker.config.enabled", "true")::containsKey)).isEmpty();
    }
}
