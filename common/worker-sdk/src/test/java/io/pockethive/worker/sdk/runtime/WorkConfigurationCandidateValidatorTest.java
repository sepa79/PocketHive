package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkConfigurationCandidateValidatorTest {
    private final WorkConfigurationCandidateValidator validator = new WorkConfigurationCandidateValidator(
        new io.pockethive.work.config.composition.CurrentWorkConfigurationProviders().workConfigurationParser());

    @Test
    void passesNonWorkConfigurationWithoutParsing() {
        assertThatCode(() -> validator.validate(csvState(), Map.of("enabled", true))).doesNotThrowAnyException();
    }

    @Test
    void composesCsvStartupAndNoneOutputWithoutMutatingSources() {
        var state = csvState();
        var source = new LinkedHashMap<String, Object>();
        source.put("inputs", Map.of("csv", Map.of("ratePerSec", 2.0)));

        assertThatCode(() -> validator.validate(state, source)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(state,
            Map.of("inputs", Map.of("csv", Map.of("unknown", true))))).isInstanceOf(WorkConfigurationException.class);
        org.assertj.core.api.Assertions.assertThat(source).containsKey("inputs");
    }

    @Test
    void rejectsRawTypeMismatchUnselectedBlockAndNonMapRoot() {
        var state = csvState();
        assertThatThrownBy(() -> validator.validate(state,
            Map.of("inputs", Map.of("type", "SCHEDULER", "csv", Map.of("ratePerSec", 2.0)))))
            .isInstanceOf(WorkConfigurationException.class);
        assertThatThrownBy(() -> validator.validate(state,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", 2.0), "redis", Map.of()))))
            .isInstanceOf(WorkConfigurationException.class);
        assertThatThrownBy(() -> validator.validate(state, Map.of("inputs", "invalid")))
            .isInstanceOf(WorkConfigurationException.class);
    }

    private static WorkerState csvState() {
        var definition = new WorkerDefinition("csv", Object.class, WorkerInputType.CSV_DATASET, "role",
            WorkIoBindings.none(), Void.class, io.pockethive.work.config.binding.WorkInputConfig.class,
            io.pockethive.work.config.binding.WorkOutputConfig.class, WorkerOutputType.NONE, "csv", Set.of());
        var state = new WorkerState(definition);
        state.initializeInputStartup(WorkerInputType.CSV_DATASET, Map.of("filePath", "/data.csv", "ratePerSec", 1.0,
            "rotate", false, "skipHeader", true, "delimiter", ",", "charset", "UTF-8",
            "startupDelaySeconds", 0, "tickIntervalMs", 1000));
        return state;
    }
}
