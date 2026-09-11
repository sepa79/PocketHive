package io.pockethive.work.local.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchedulerSettingsParserTest {
    private final SchedulerSettingsParser parser = new SchedulerSettingsParser();

    @Test
    void delegatesDefaultsAndExactValuesWithoutTreatingNullAsOmission() {
        var settings = parser.parse(Map.of("ratePerSec", "2.5", "maxMessages", "9223372036854775807"), "inputs.scheduler");
        assertThat(settings.ratePerSec()).isEqualTo(2.5);
        assertThat(settings.maxMessages()).isEqualTo(Long.MAX_VALUE);
        assertThat(settings.initialDelayMs()).isZero();
        assertThat(settings.tickIntervalMs()).isEqualTo(1000);
        assertThat(settings.maxPendingTicks()).isEqualTo(1);
        for (String field : java.util.List.of("ratePerSec", "maxMessages", "initialDelayMs", "tickIntervalMs", "maxPendingTicks")) {
            var fields = new LinkedHashMap<String, Object>(Map.of("ratePerSec", 1, "maxMessages", 0));
            fields.put(field, null);
            assertThatThrownBy(() -> parser.parse(fields, "inputs.scheduler"))
                .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("inputs.scheduler." + field);
        }
    }

    @Test
    void rejectsUnknownFieldsAndInvalidRootsAndRetainsResetContract() {
        for (Object value : java.util.List.of("bad", java.util.List.of(), Map.of("ratePerSec", 1, "maxMessages", 0, "surprise", 1),
            Map.of("ratePerSec", 1, "maxMessages", 0, "reset", "true"))) {
            assertThatThrownBy(() -> parser.parse(value, "inputs.scheduler")).isInstanceOf(WorkConfigurationException.class);
        }
        assertThat(parser.parse(Map.of("ratePerSec", 1, "maxMessages", 0, "reset", true), "inputs.scheduler").maxMessages()).isZero();
        assertThat(parser.validate(Map.of(), "inputs.scheduler", WorkConfigurationMode.RESOLVED).problems()).hasSize(2);
    }

    @Test
    void defersExpressionsWithoutProducingPartialSettings() {
        var fields = Map.of("ratePerSec", "{{ rate }}", "maxMessages", "{{ limit }}", "reset", "{{ reset }}");
        var authored = parser.validate(fields, "inputs.scheduler", WorkConfigurationMode.AUTHORING);
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).hasSize(3);
        assertThat(authored.settings()).isNull();
        assertThat(parser.validate(fields, "inputs.scheduler", WorkConfigurationMode.RESOLVED).problems()).hasSize(3);
    }
}
