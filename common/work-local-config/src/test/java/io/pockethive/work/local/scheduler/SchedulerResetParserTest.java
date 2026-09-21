package io.pockethive.work.local.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulerResetParserTest {
    private final SchedulerResetParser parser = new SchedulerResetParser();

    @Test
    void acceptsOnlyBooleanDeclarations() {
        assertThat(parser.parse(true, SchedulerResetParser.PATH)).isTrue();
        assertThat(parser.parse(false, SchedulerResetParser.PATH)).isFalse();
        for (Object invalid : new Object[]{null, "true", "false", "secret-reset-value", "", 0, 1, List.of(), Map.of()}) {
            assertThatThrownBy(() -> parser.parse(invalid, SchedulerResetParser.PATH))
                .isInstanceOf(WorkConfigurationException.class)
                .hasMessageContaining(SchedulerResetParser.PATH).hasMessageContaining("must be boolean")
                .hasMessageNotContaining("secret-reset-value");
        }
    }

    @Test
    void authoringDefersExpressionsButRuntimeRejectsThem() {
        var authored = parser.validate("{{ true }}", SchedulerResetParser.PATH, WorkConfigurationMode.AUTHORING);
        assertThat(authored.resetRequested()).isNull();
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactly(SchedulerResetParser.PATH);
        assertThatThrownBy(() -> parser.parse("{{ true }}", SchedulerResetParser.PATH))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");
        var invalid = parser.validate("true", SchedulerResetParser.PATH, WorkConfigurationMode.AUTHORING);
        assertThat(invalid.resetRequested()).isNull();
        assertThat(invalid.problems()).hasSize(1);
        assertThat(invalid.deferredPaths()).isEmpty();
    }
}
