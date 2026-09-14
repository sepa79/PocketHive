package io.pockethive.work.config;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class WorkIoTypeParserTest {
    @ParameterizedTest
    @ValueSource(strings = {"RABBITMQ", "rabbitmq", " RaBbItMq ", "\tRABBITMQ\t"})
    void acceptsTheSameSelectorForBootstrapAndCompleteParsing(String selector) {
        assertThat(WorkIoTypeParser.parse(selector, List.of(WorkerInputType.RABBITMQ)))
            .isEqualTo(WorkerInputType.RABBITMQ);
        assertThat(WorkIoTypeParser.matches(selector, WorkerInputType.RABBITMQ)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "MEMORY"})
    void unmatchedSelectorDoesNotActivateRabbitButCompleteParsingStillRejectsIt(String selector) {
        assertThat(WorkIoTypeParser.matches(selector, WorkerInputType.RABBITMQ)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() ->
            WorkIoTypeParser.parse(selector, List.of(WorkerInputType.RABBITMQ)));
    }

    @Test
    void explicitlyDeclaredCustomTypeUsesTheSameNormalization() {
        WorkIoType memory = new TestType("MEMORY", "memory");
        assertThat(WorkIoTypeParser.parse(" Memory ", List.of(memory, WorkerInputType.RABBITMQ)))
            .isEqualTo(memory);
        assertThat(WorkIoTypeParser.matches(" Memory ", memory)).isTrue();
        assertThat(WorkIoTypeParser.matches(" Memory ", WorkerInputType.RABBITMQ)).isFalse();
    }

    @Test
    void completeParsingRejectsAmbiguousDefinitionsEvenWhenTheSelectorMatches() {
        assertThat(WorkIoTypeParser.matches(" RABBITMQ ", WorkerInputType.RABBITMQ)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> WorkIoTypeParser.parse(" RABBITMQ ",
                List.of(WorkerInputType.RABBITMQ, WorkerOutputType.RABBITMQ)))
            .withMessage("Multiple type definitions are registered.");
    }

    private record TestType(String name, String settingsKey) implements WorkIoType { }
}
