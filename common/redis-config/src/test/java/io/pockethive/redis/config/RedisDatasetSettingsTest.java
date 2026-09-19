package io.pockethive.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisDatasetSettingsTest {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    @Test
    void composesConcreteSettingsThroughTheExistingFieldContracts() {
        var settings = parseRedisDatasetSettings(singleSettings());

        assertThat(settings.connection()).isEqualTo(new RedisConnectionSettings("redis", 6379, null, null, false));
        assertThat(settings.sourceMode()).isEqualTo(RedisDatasetSourceMode.SINGLE);
        assertThat(settings.listName()).isEqualTo("dataset");
        assertThat(settings.sources()).isEmpty();
        assertThat(settings.pickStrategy()).isEqualTo(RedisDatasetPickStrategy.WEIGHTED_RANDOM);
        assertThat(settings.ratePerSec()).isEqualTo(2.5);
        assertThat(settings.initialDelayMs()).isZero();
        assertThat(settings.tickIntervalMs()).isEqualTo(1000L);
    }

    @Test
    void preservesOmittedSourcesForSingleModeButRejectsAnExplicitNullSourcesDeclaration() {
        var omitted = singleSettings();
        omitted.remove("sources");
        assertThat(parseRedisDatasetSettings(omitted).sourceMode()).isEqualTo(RedisDatasetSourceMode.SINGLE);

        var explicitNull = singleSettings();
        explicitNull.put("sources", null);
        var result = parser.validateRedisDatasetSettings(explicitNull, "inputs.redis", WorkConfigurationMode.RESOLVED);
        assertThat(result.settings()).isNull();
        assertThat(result.problems()).extracting(WorkConfigurationProblem::path).contains("inputs.redis.sources");
    }

    @Test
    void permitsAbsentListNameForMultipleSources() {
        var values = singleSettings();
        values.remove("listName");
        values.put("sources", List.of(Map.of("listName", "first", "weight", "1")));

        var settings = parseRedisDatasetSettings(values);
        assertThat(settings.sourceMode()).isEqualTo(RedisDatasetSourceMode.MULTIPLE);
        assertThat(settings.listName()).isNull();
        assertThat(settings.sources()).containsExactly(new RedisDatasetSource("first", 1));
    }

    @Test
    void aggregatesFieldErrorsWithoutExposingPartialSettings() {
        var values = singleSettings();
        values.put("host", 1);
        values.put("pickStrategy", "random");
        values.put("ratePerSec", -1);
        values.put("initialDelayMs", null);
        values.put("unexpected", true);

        var result = parser.validateRedisDatasetSettings(values, "inputs.redis", WorkConfigurationMode.RESOLVED);
        assertThat(result.settings()).isNull();
        assertThat(result.problems()).extracting(WorkConfigurationProblem::path).containsExactlyInAnyOrder(
            "inputs.redis.unexpected", "inputs.redis.host", "inputs.redis.pickStrategy", "inputs.redis.ratePerSec",
            "inputs.redis.initialDelayMs");
    }

    @Test
    void defersSymbolicFieldsInAuthoringWithoutProducingSettingsAndRejectsThemWhenResolved() {
        var values = singleSettings();
        values.put("sources", "{{ datasetSources }}");
        values.put("listName", "{{ datasetName }}");
        values.put("pickStrategy", "{{ strategy }}");

        var authored = parser.validateRedisDatasetSettings(values, "inputs.redis", WorkConfigurationMode.AUTHORING);
        assertThat(authored.settings()).isNull();
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactlyInAnyOrder("inputs.redis.sources", "inputs.redis.listName",
            "inputs.redis.pickStrategy");
        assertThatThrownBy(() -> parser.parseRedisDatasetSettings(values, "inputs.redis"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");
    }

    private RedisDatasetSettings parseRedisDatasetSettings(Map<String, Object> values) {
        return parser.parseRedisDatasetSettings(values, "inputs.redis");
    }

    private static Map<String, Object> singleSettings() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", "redis");
        values.put("port", "6379");
        values.put("ssl", false);
        values.put("listName", " dataset ");
        values.put("sources", List.of());
        values.put("pickStrategy", " weighted_random ");
        values.put("ratePerSec", "2.5");
        return values;
    }
}
