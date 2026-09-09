package io.pockethive.work.config;

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

class WorkConfigurationParserTest {
    private final WorkConfigurationParser parser = new WorkConfigurationParser();

    @Test
    void parsesDecodedAndRawRoutesWithTheSameOrderAndSignificantText() {
        var definition = new RedisRouteDefinition(" ok ", " route ", "^blue$", " list ");
        var raw = Map.of("match", " ok ", "header", " route ", "headerMatch", "^blue$", "list", " list ");

        for (Object first : List.of(definition, raw)) {
            var routes = parser.parseRedisRoutes(List.of(first, Map.of("match", ".*", "list", "second")), "routes");
            assertThat(routes).extracting(RedisRoute::list).containsExactly(" list ", "second");
            assertThat(routes.getFirst().headerName()).isEqualTo(" route ");
            assertThat(routes.getFirst().headerPattern().matcher("blue").find()).isTrue();
            assertThat(routes.getFirst().payloadPattern().matcher(" ok ").find()).isTrue();
            assertThat(routes.getFirst().payloadPattern().matcher("ok").find()).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidRoutes")
    void authoringAndRuntimeRejectTheSameConcreteErrors(Object routes, String path, String message) {
        var authoring = parser.validateRedisRoutes(routes, "routes", WorkConfigurationMode.AUTHORING);
        var resolved = parser.validateRedisRoutes(routes, "routes", WorkConfigurationMode.RESOLVED);

        assertThat(authoring).isEqualTo(resolved);
        assertThat(resolved.problems()).anySatisfy(problem -> {
            assertThat(problem.path()).isEqualTo(path);
            assertThat(problem.message()).contains(message);
        });
        assertThatThrownBy(() -> parser.parseRedisRoutes(routes, "routes"))
            .isInstanceOfSatisfying(WorkConfigurationException.class,
                error -> assertThat(error.problems()).isEqualTo(resolved.problems()));
    }

    static Stream<Arguments> invalidRoutes() {
        return Stream.of(
            Arguments.of("literal", "routes", "must be a list"),
            Arguments.of(Arrays.asList((Object) null), "routes[0]", "must be an object"),
            Arguments.of(List.of(Map.of("list", "out")), "routes[0]", "requires match and/or header"),
            Arguments.of(List.of(Map.of("match", ".*", "list", " ")), "routes[0].list", "must not be blank"),
            Arguments.of(List.of(Map.of("header", "key", "list", "out")), "routes[0].headerMatch", "must be configured"),
            Arguments.of(List.of(Map.of("match", "[", "list", "out")), "routes[0].match", "regex is invalid"),
            Arguments.of(List.of(Map.of("header", "key", "headerMatch", "[", "list", "out")),
                "routes[0].headerMatch", "regex is invalid"),
            Arguments.of(List.of(Map.of("match", ".*", "list", "out", "typo", "x")), "routes[0].typo", "Unknown"),
            Arguments.of(List.of(Map.of("match", ".*", "list", 7)), "routes[0].list", "must be a string"));
    }

    @Test
    void reportsSymbolicFieldsAsDeferredAndRejectsThemAtRuntime() {
        var routes = List.of(Map.of("match", "{{ vars.pattern }}", "list", "{% if vars.blue %}blue{% endif %}"));
        var authored = parser.validateRedisRoutes(routes, "routes", WorkConfigurationMode.AUTHORING);

        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactlyInAnyOrder("routes[0].match", "routes[0].list");
        assertThatThrownBy(() -> parser.parseRedisRoutes(routes, "routes"))
            .isInstanceOfSatisfying(WorkConfigurationException.class, error ->
                assertThat(error.problems()).extracting(WorkConfigurationProblem::path)
                    .containsExactlyInAnyOrder("routes[0].match", "routes[0].list"));
    }

    @Test
    void defersSymbolicArraysWithoutTreatingConcreteErrorsAsDeferred() {
        var authored = parser.validateRedisRoutes("{{ vars.routes }}", "routes", WorkConfigurationMode.AUTHORING);
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactly("routes");
        assertThatThrownBy(() -> parser.parseRedisRoutes("{{ vars.routes }}", "routes"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("must be rendered");

        var mixed = parser.validateRedisRoutes(List.of(Map.of("header", "{{ vars.header }}", "list", "out")),
            "routes", WorkConfigurationMode.AUTHORING);
        assertThat(mixed.deferredPaths()).containsExactly("routes[0].header");
        assertThat(mixed.problems()).extracting(WorkConfigurationProblem::path).containsExactly("routes[0].headerMatch");
    }

    @Test
    void defersHeaderRequirementWhenTheSymbolicHeaderCanDisappear() {
        var authored = parser.validateRedisRoutes(List.of(Map.of("match", ".*", "header", "{{ '' }}", "list", "out")),
            "routes", WorkConfigurationMode.AUTHORING);
        assertThat(authored.problems()).isEmpty();
        assertThat(authored.deferredPaths()).containsExactly("routes[0].header");
        assertThat(authored.routes()).isEmpty();
        assertThat(authored.isEmpty()).isFalse();

        assertThat(parser.parseRedisRoutes(List.of(Map.of("match", ".*", "header", "", "list", "out")), "routes"))
            .singleElement().satisfies(route -> assertThat(route.headerName()).isNull());
        assertThatThrownBy(() -> parser.parseRedisRoutes(List.of(Map.of("match", ".*", "header", "key", "list", "out")), "routes"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("headerMatch");
    }

    @Test
    void reportsOnlyKnownEmptyListsAsEmptyAndDoesNotExposePartialRoutes() {
        assertThat(parser.validateRedisRoutes(null, "routes", WorkConfigurationMode.AUTHORING).isEmpty()).isTrue();
        assertThat(parser.validateRedisRoutes(List.of(), "routes", WorkConfigurationMode.AUTHORING).isEmpty()).isTrue();
        for (Object second : List.of(Map.of("match", "[", "list", "out"), Map.of("match", "{{ vars.pattern }}", "list", "out"))) {
            var report = parser.validateRedisRoutes(List.of(Map.of("match", ".*", "list", "valid"), second),
                "routes", WorkConfigurationMode.AUTHORING);
            assertThat(report.isEmpty()).isFalse();
            assertThat(report.routes()).isEmpty();
        }
    }

    @Test
    void rejectsTheWholeListWhenALaterRouteIsInvalid() {
        assertThatThrownBy(() -> parser.parseRedisRoutes(List.of(
            Map.of("match", ".*", "list", "first"), Map.of("match", "[", "list", "second")), "routes"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("routes[1].match");
        assertThat(parser.parseRedisRoutes(null, "routes")).isEmpty();
    }
}
