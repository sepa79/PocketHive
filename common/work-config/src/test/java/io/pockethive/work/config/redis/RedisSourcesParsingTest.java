package io.pockethive.work.config.redis;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class RedisSourcesParsingTest {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    @Test
    void normalizesNamesAndWeightsAndPreservesOrderForRawAndBoundValues() {
        var bound = new RedisDatasetSource(" red ", "2.5");
        for (Object first : List.of(bound, Map.of("listName", " red ", "weight", "2.5"))) {
            var sources = parser.parseRedisSources(List.of(first, Map.of("listName", "blue", "weight", 1)), "sources");
            assertThat(sources).containsExactly(new RedisDatasetSource("red", 2.5), new RedisDatasetSource("blue", 1));
            assertThatThrownBy(() -> sources.clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidSources")
    void rejectsTheSameConcreteErrorsInAuthoringAndRuntime(Object sources, String path, String message) {
        var authored = parser.validateRedisSources(sources, "sources", WorkConfigurationMode.AUTHORING);
        var resolved = parser.validateRedisSources(sources, "sources", WorkConfigurationMode.RESOLVED);
        assertThat(authored).isEqualTo(resolved);
        assertThat(resolved.sources()).isEmpty();
        assertThat(resolved.isEmpty()).isFalse();
        assertThat(resolved.problems()).anySatisfy(problem -> {
            assertThat(problem.path()).isEqualTo(path);
            assertThat(problem.message()).contains(message);
        });
        assertThatThrownBy(() -> parser.parseRedisSources(sources, "sources"))
            .isInstanceOfSatisfying(WorkConfigurationException.class,
                error -> assertThat(error.problems()).isEqualTo(resolved.problems()));
    }

    static Stream<Arguments> invalidSources() {
        return Stream.of(
            Arguments.of(null, "sources", "must be a list"),
            Arguments.of("literal", "sources", "must be a list"),
            Arguments.of(Arrays.asList((Object) null), "sources[0]", "must be an object"),
            Arguments.of(List.of(Map.of("listName", "red")), "sources[0].weight", "must be configured"),
            Arguments.of(List.of(Map.of("listName", " ", "weight", 1)), "sources[0].listName", "nonblank text"),
            Arguments.of(List.of(Map.of("listName", "\u0000", "weight", 1)), "sources[0].listName", "nonblank text"),
            Arguments.of(List.of(Map.of("listName", 7, "weight", 1)), "sources[0].listName", "nonblank text"),
            Arguments.of(List.of(Map.of("listName", "red", "weight", "no")), "sources[0].weight", "must be a number"),
            Arguments.of(List.of(Map.of("listName", "red", "weight", Double.NaN)), "sources[0].weight", "finite"),
            Arguments.of(List.of(Map.of("listName", "red", "weight", 0)), "sources[0].weight", "> 0"),
            Arguments.of(List.of(Map.of("listName", "red", "weight", 1, "typo", true)), "sources[0].typo", "Unknown"),
            Arguments.of(List.of(new RedisDatasetSource("red", 1), Map.of("listName", " red ", "weight", 2)),
                "sources[1].listName", "duplicate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{ vars.sources }}", "{% if true %}[]{% endif %}"})
    void defersSymbolicListsAndRejectsThemAtRuntime(String value) {
        var result = parser.validateRedisSources(value, "sources", WorkConfigurationMode.AUTHORING);
        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).containsExactly("sources");
        assertThat(result.isEmpty()).isFalse();
        assertThatThrownBy(() -> parser.parseRedisSources(value, "sources"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");
    }

    @Test
    void symbolicFieldsDoNotHideConcreteErrorsOrExposePartialSources() {
        var values = List.of(Map.of("listName", "red", "weight", 1),
            Map.of("listName", "{{ vars.name }}", "weight", "bad"),
            Map.of("listName", "blue", "weight", "{{ vars.weight }}"));
        var result = parser.validateRedisSources(values, "sources", WorkConfigurationMode.AUTHORING);
        assertThat(result.sources()).isEmpty();
        assertThat(result.deferredPaths()).containsExactly("sources[1].listName", "sources[2].weight");
        assertThat(result.problems()).extracting(WorkConfigurationProblem::path).containsExactly("sources[1].weight");
        assertThatThrownBy(() -> parser.parseRedisSources(values, "sources"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");
        assertThat(parser.validateRedisSources(List.of(), "sources", WorkConfigurationMode.AUTHORING).isEmpty()).isTrue();
    }
}
