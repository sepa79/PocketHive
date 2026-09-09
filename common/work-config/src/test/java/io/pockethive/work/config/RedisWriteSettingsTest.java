package io.pockethive.work.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedisWriteSettingsTest {
    private final WorkConfigurationParser parser = new WorkConfigurationParser();

    @Test
    void resolvesTypedAndTextualSettingsWithoutDefaults() {
        var text = parser.parseRedisWriteSettings(" first ", " lpush ", " 3 ", "redis");
        assertThat(text.sourceStep()).isEqualTo(RedisPayloadSource.FIRST);
        assertThat(text.pushDirection()).isEqualTo(RedisPushDirection.LPUSH);
        assertThat(text.maxLen()).isEqualTo(3);
        for (int length : List.of(-1, 0, Integer.MAX_VALUE)) {
            var typed = parser.parseRedisWriteSettings(RedisPayloadSource.LAST, RedisPushDirection.RPUSH, length, "redis");
            assertThat(typed.maxLen()).isEqualTo(length);
        }
    }

    @Test
    void rejectsInvalidEnumsAndAllMissingFieldsWithCanonicalPaths() {
        var missing = parser.validateRedisWriteSettings(null, null, null, "redis", WorkConfigurationMode.RESOLVED);
        assertThat(missing.settings()).isNull();
        assertThat(missing.problems()).extracting(WorkConfigurationProblem::path)
            .containsExactly("redis.sourceStep", "redis.pushDirection", "redis.maxLen");
        assertThatThrownBy(() -> parser.parseRedisWriteSettings("MIDDLE", "RPUSH", -1, "redis"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("sourceStep");
        assertThatThrownBy(() -> parser.parseRedisWriteSettings("FIRST", "PUSH", -1, "redis"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("pushDirection");
    }

    @Test
    void rejectsFractionalOverflowingAndOutOfRangeLimitsInBothModes() {
        for (var mode : WorkConfigurationMode.values()) {
            for (Object value : List.of(-2, 1.5, Double.NaN, 2147483648L, "1.0", "2147483648",
                new BigDecimal("1.0000000000000000001"))) {
                var result = parser.validateRedisWriteSettings("FIRST", "RPUSH", value, "redis", mode);
                assertThat(result.settings()).isNull();
                assertThat(result.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis.maxLen");
            }
        }
    }

    @Test
    void defersExpressionsOnlyInAuthoringAndStillReportsConcreteErrors() {
        var authored = parser.validateRedisWriteSettings("{{ 'FIRST' }}", "{{ 'LPUSH' }}", "{{ 5 }}", "redis",
            WorkConfigurationMode.AUTHORING);
        assertThat(authored.settings()).isNull();
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactly("redis.sourceStep", "redis.pushDirection", "redis.maxLen");
        var invalid = parser.validateRedisWriteSettings("{{ 'FIRST' }}", "WRONG", -2, "redis", WorkConfigurationMode.AUTHORING);
        assertThat(invalid.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis.pushDirection", "redis.maxLen");
        assertThatThrownBy(() -> parser.parseRedisWriteSettings("{{ 'FIRST' }}", "RPUSH", -1, "redis"))
            .hasMessageContaining("must be rendered");
    }
}
