package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RedisDatasetSelectionTest {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    @Test
    void resolvesSingleOrMultipleSourcesWithCanonicalValues() {
        var single = parser.parseRedisDatasetSelection(" red ", List.of(), "redis");
        assertThat(single.mode()).isEqualTo(RedisDatasetSourceMode.SINGLE);
        assertThat(single.listName()).isEqualTo("red");
        var multiple = parser.parseRedisDatasetSelection("", List.of(Map.of("listName", " blue ", "weight", 1)), "redis");
        assertThat(multiple.mode()).isEqualTo(RedisDatasetSourceMode.MULTIPLE);
        assertThat(multiple.listName()).isNull();
        assertThat(multiple.sources()).containsExactly(new RedisDatasetSource("blue", 1));
    }

    @ParameterizedTest
    @MethodSource("invalidChoices")
    void rejectsTheSameConcreteChoicesInBothModes(Object name, Object sources, String path) {
        var authored = parser.validateRedisDatasetSelection(name, sources, "redis", WorkConfigurationMode.AUTHORING);
        var resolved = parser.validateRedisDatasetSelection(name, sources, "redis", WorkConfigurationMode.RESOLVED);
        assertThat(authored).isEqualTo(resolved);
        assertThat(resolved.mode()).isEqualTo(RedisDatasetSourceMode.UNRESOLVED);
        assertThat(resolved.listName()).isNull();
        assertThat(resolved.sources()).isEmpty();
        assertThat(resolved.problems()).extracting(WorkConfigurationProblem::path).contains(path);
        assertThatThrownBy(() -> parser.parseRedisDatasetSelection(name, sources, "redis"))
            .isInstanceOf(WorkConfigurationException.class);
    }

    static Stream<Arguments> invalidChoices() {
        return Stream.of(
            Arguments.of(null, List.of(), "redis"),
            Arguments.of("\u0000", List.of(), "redis"),
            Arguments.of("red", List.of(new RedisDatasetSource("blue", 1)), "redis"),
            Arguments.of(7, List.of(), "redis.listName"),
            Arguments.of("red", null, "redis.sources"));
    }

    @Test
    void defersOnlyChoicesThatRenderingCanResolve() {
        var name = parser.validateRedisDatasetSelection("{{ 'red' }}", List.of(), "redis", WorkConfigurationMode.AUTHORING);
        assertThat(name.problems()).isEmpty();
        assertThat(name.deferredPaths()).containsExactly("redis.listName");
        var list = parser.validateRedisDatasetSelection("red", "{{ '[]' }}", "redis", WorkConfigurationMode.AUTHORING);
        assertThat(list.problems()).isEmpty();
        assertThat(list.mode()).isEqualTo(RedisDatasetSourceMode.UNRESOLVED);
        assertThat(list.deferredPaths()).containsExactly("redis.sources");
        var conflict = parser.validateRedisDatasetSelection("red",
            List.of(Map.of("listName", "blue", "weight", "{{ 1 }}")), "redis", WorkConfigurationMode.AUTHORING);
        assertThat(conflict.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis");
        assertThat(conflict.deferredPaths()).containsExactly("redis.sources[0].weight");
        assertThatThrownBy(() -> parser.parseRedisDatasetSelection("{{ 'red' }}", List.of(), "redis"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");
    }
}
