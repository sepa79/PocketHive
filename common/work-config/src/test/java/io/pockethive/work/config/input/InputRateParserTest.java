package io.pockethive.work.config.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputRateParserTest {
    private final InputRateParser parser = new InputRateParser();
    private static final String PATH = InputRateParser.SCHEDULER_PATH;

    @Test
    void acceptsNumbersAndNumericTextWithoutAnUpperLimitInBothModes() {
        for (Object value : List.of(0, 2500.5, " 3.0 ", Double.MAX_VALUE, "1e5")) {
            double parsed = parser.parse(value, PATH);
            assertThat(parser.validate(value, PATH, WorkConfigurationMode.AUTHORING).ratePerSec()).isEqualTo(parsed);
        }
        assertThat(parser.parse(" 3.0 ", PATH)).isEqualTo(3.0);
        assertThat(parser.parse(0, PATH)).isZero();
    }

    @Test
    void rejectsInvalidValuesWithTheSameSafeProblemInBothModes() {
        Object[] invalid = {null, true, "", "fast-secret", -0.1, Double.NaN, Double.POSITIVE_INFINITY,
            "-1", "1e999", List.of(1), Map.of("value", 1)};
        for (Object value : invalid) {
            var resolved = parser.validate(value, PATH, WorkConfigurationMode.RESOLVED);
            var authoring = parser.validate(value, PATH, WorkConfigurationMode.AUTHORING);
            assertThat(resolved.ratePerSec()).isNull();
            assertThat(resolved.problems()).singleElement().satisfies(problem -> {
                assertThat(problem.path()).isEqualTo(PATH);
                assertThat(problem.message()).contains("finite number >= 0.0").doesNotContain("secret");
            });
            assertThat(authoring.problems()).isEqualTo(resolved.problems());
            assertThatThrownBy(() -> parser.parse(value, PATH)).isInstanceOf(WorkConfigurationException.class)
                .hasNoCause();
        }
    }

    @Test
    void defersExpressionsOnlyInAuthoringAndRequiresTheirRenderedValueAtRuntime() {
        for (String value : List.of("{{ 3 }}", "{% if true %}3{% endif %}")) {
            var result = parser.validate(value, PATH, WorkConfigurationMode.AUTHORING);
            assertThat(result.ratePerSec()).isNull();
            assertThat(result.problems()).isEmpty();
            assertThat(result.deferredPaths()).containsExactly(PATH);
            assertThatThrownBy(() -> parser.parse(value, PATH)).isInstanceOf(WorkConfigurationException.class)
                .hasMessageContaining("must be rendered");
        }
    }
}
