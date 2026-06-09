package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerWorkInputTest {

    private final PebbleTemplateRenderer renderer = new PebbleTemplateRenderer();

    @Test
    void resolvesNumericMaxMessages() {
        var resolved = SchedulerWorkInput.resolveMaxMessages(10, Map.of(), renderer);

        assertThat(resolved).hasValue(10L);
    }

    @Test
    void resolvesMaxMessagesFromScenarioVariablesTemplate() {
        Map<String, Object> rawConfig = Map.of("vars", Map.of("userCount", 10_000));

        var resolved = SchedulerWorkInput.resolveMaxMessages("{{ vars.userCount }}", rawConfig, renderer);

        assertThat(resolved).hasValue(10_000L);
    }

    @Test
    void rejectsMaxMessagesTemplateThatDoesNotResolveToWholeNumber() {
        Map<String, Object> rawConfig = Map.of("vars", Map.of("userCount", "10.5"));

        assertThatThrownBy(() -> SchedulerWorkInput.resolveMaxMessages("{{ vars.userCount }}", rawConfig, renderer))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative whole number");
    }

    @Test
    void rejectsFractionalNumericMaxMessages() {
        assertThatThrownBy(() -> SchedulerWorkInput.resolveMaxMessages(10.5, Map.of(), renderer))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-negative whole number")
            .hasMessageContaining("10.5");
    }
}
