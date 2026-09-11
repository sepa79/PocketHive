package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisOutputTargetsTest {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    @Test
    void normalizesTargetTextAndPreservesPerMessageTemplatesInBothModes() {
        for (var mode : WorkConfigurationMode.values()) {
            var result = parser.validateRedisOutputTargets(List.of(), " out ", " {{ headers.target }} ", "redis", mode);
            assertThat(result.problems()).isEmpty();
            assertThat(result.deferredPaths()).isEmpty();
            assertThat(result.defaultList()).isEqualTo("out");
            assertThat(result.targetListTemplate()).isEqualTo("{{ headers.target }}");
        }
    }

    @Test
    void rejectsMissingTargetsAndWrongTypesWithoutPartialResults() {
        assertThatThrownBy(() -> parser.parseRedisOutputTargets(null, "\u0000", " ", "redis"))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("at least one target");
        for (var mode : WorkConfigurationMode.values()) {
            for (Object invalid : List.of(7, Map.of("nested", "out"), List.of("out"))) {
                var invalidList = parser.validateRedisOutputTargets(List.of(), invalid, "valid", "redis", mode);
                var invalidTemplate = parser.validateRedisOutputTargets(List.of(), "valid", invalid, "redis", mode);
                assertThat(invalidList.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis.defaultList");
                assertThat(invalidTemplate.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis.targetListTemplate");
                assertThat(invalidList.targetListTemplate()).isNull();
                assertThat(invalidTemplate.defaultList()).isNull();
            }
        }
    }

    @Test
    void delegatesRouteValidationAndReusesCompiledRoutesForUpdates() {
        var initial = parser.parseRedisOutputTargets(List.of(Map.of("match", "ok", "list", "routed")), null, null, "redis");
        var update = parser.parseRedisOutputTargets(initial.routes(), null, " {{ headers.target }} ", "redis");
        assertThat(update.routes()).extracting(RedisRoute::list).containsExactly("routed");
        assertThatThrownBy(() -> parser.parseRedisOutputTargets(List.of(Map.of("match", "(", "list", "out")),
            "valid", null, "redis")).hasMessageContaining("redis.routes[0].match");
    }

    @Test
    void defersBootstrapExpressionsWithoutInventingAMissingTargetError() {
        var list = parser.validateRedisOutputTargets(List.of(), "{{ vars.list }}", null, "redis", WorkConfigurationMode.AUTHORING);
        var routes = parser.validateRedisOutputTargets("{{ vars.routes }}", null, null, "redis", WorkConfigurationMode.AUTHORING);
        assertThat(list.problems()).isEmpty();
        assertThat(list.defaultList()).isNull();
        assertThat(list.deferredPaths()).containsExactly("redis.defaultList");
        assertThat(routes.problems()).isEmpty();
        assertThat(routes.deferredPaths()).containsExactly("redis.routes");
        assertThatThrownBy(() -> parser.parseRedisOutputTargets(List.of(), "{{ vars.list }}", null, "redis"))
            .hasMessageContaining("must be rendered");
    }
}
