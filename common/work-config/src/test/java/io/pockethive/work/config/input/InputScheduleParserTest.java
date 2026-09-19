package io.pockethive.work.config.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkerInputType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class InputScheduleParserTest {
    private final InputScheduleParser parser = new InputScheduleParser();

    @ParameterizedTest
    @EnumSource(InputScheduleField.class)
    void rejectsMalformedValuesAndDefersOnlyAuthoringExpressions(InputScheduleField field) {
        for (Object bad : new Object[]{null, "", " ", true, -1, 100.5, "100.5", "NaN", "Infinity", Map.of(),
            new BigInteger("18446744073709551616")}) {
            var result = parser.validate(bad, field, "input." + field.key(), WorkConfigurationMode.RESOLVED);
            assertThat(result.value()).isNull();
            assertThat(result.problems()).singleElement().satisfies(problem ->
                assertThat(problem.path()).isEqualTo("input." + field.key()));
            assertThat(result.deferredPaths()).isEmpty();
        }
        var deferred = parser.validate("{{ vars.limit }}", field, "input", WorkConfigurationMode.AUTHORING);
        assertThat(deferred.value()).isNull();
        assertThat(deferred.problems()).isEmpty();
        assertThat(deferred.deferredPaths()).containsExactly("input");
        assertThatThrownBy(() -> parser.parse("{{ vars.limit }}", field, "input"))
            .isInstanceOf(WorkConfigurationException.class);
    }

    @Test
    void preservesExactLongLimitsAcrossNumberAndTextDeclarations() {
        for (Object value : new Object[]{Long.MAX_VALUE, "9223372036854775807", new BigDecimal("9223372036854775807.0")}) {
            assertThat(parser.parse(value, InputScheduleField.MAX_MESSAGES, "limit")).isEqualTo(Long.MAX_VALUE);
        }
        for (Object value : new Object[]{new BigInteger("9223372036854775808"), "9223372036854775808", (double) Long.MAX_VALUE}) {
            assertThatThrownBy(() -> parser.parse(value, InputScheduleField.MAX_MESSAGES, "limit"))
                .isInstanceOf(WorkConfigurationException.class);
        }
        assertThat(parser.parse("2e3", InputScheduleField.MAX_MESSAGES, "limit")).isEqualTo(2000L);
        assertThat(parser.parse(0, InputScheduleField.MAX_MESSAGES, "limit")).isZero();
        assertThat(parser.parse(100, InputScheduleField.TICK_INTERVAL_MS, "tick")).isEqualTo(100L);
        assertThatThrownBy(() -> parser.parse(99, InputScheduleField.TICK_INTERVAL_MS, "tick"))
            .isInstanceOf(WorkConfigurationException.class);
        assertThatThrownBy(() -> parser.parse(2147483648L, InputScheduleField.MAX_PENDING_TICKS, "pending"))
            .isInstanceOf(WorkConfigurationException.class);
        assertThatThrownBy(() -> parser.parse(0, InputScheduleField.MAX_PENDING_TICKS, "pending"))
            .isInstanceOf(WorkConfigurationException.class);
    }

    @Test
    void keepsDurationsRepresentableWithoutOverflowOrSchedulerSaturation() {
        assertThat(parser.startupDelayMillis("9223372036", "delay")).isEqualTo(9223372036000L);
        assertThatThrownBy(() -> parser.startupDelayMillis("9223372037", "delay"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("delay");
        assertThat(parser.parse("9223372036854", InputScheduleField.TICK_INTERVAL_MS, "tick"))
            .isEqualTo(9223372036854L);
        assertThatThrownBy(() -> parser.parse("9223372036855", InputScheduleField.TICK_INTERVAL_MS, "tick"))
            .isInstanceOf(WorkConfigurationException.class);
    }

    @Test
    void defaultsApplyOnlyToOmittedOptionalDeclarations() {
        assertThat(InputScheduleParser.declaredValue(Map.of(), WorkerInputType.SCHEDULER,
            InputScheduleField.TICK_INTERVAL_MS)).isEqualTo(1000L);
        assertThat(InputScheduleParser.declaredValue(Map.of(), WorkerInputType.CSV_DATASET,
            InputScheduleField.TICK_INTERVAL_MS)).isNull();
        var explicitNull = new LinkedHashMap<String, Object>();
        explicitNull.put("tickIntervalMs", null);
        Object value = InputScheduleParser.declaredValue(explicitNull, WorkerInputType.SCHEDULER,
            InputScheduleField.TICK_INTERVAL_MS);
        assertThatThrownBy(() -> parser.parse(value, InputScheduleField.TICK_INTERVAL_MS, "tick"))
            .isInstanceOf(WorkConfigurationException.class);
    }
}
