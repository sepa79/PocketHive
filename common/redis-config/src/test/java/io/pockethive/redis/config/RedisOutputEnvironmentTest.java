package io.pockethive.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.pockethive.work.config.WorkConfigurationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisOutputEnvironmentTest {
    private final RedisOutputEnvironment owner = new RedisOutputEnvironment();

    @Test
    void projectsResolvedRoutesAndWriteOverridesWithoutChangingDeclaration() {
        var declared = Map.<String, Object>of("host", "redis", "port", 6379, "ssl", false,
            "sourceStep", "FIRST", "pushDirection", "RPUSH", "maxLen", 0,
            "routes", List.of(Map.of("match", "x.*", "list", "${DESTINATION}")));
        var output = Map.<String, Object>of("type", "REDIS", "redis", declared);
        var candidate = owner.candidate(output, Map.of("pockethive.outputs.redis.push-direction", "LPUSH")::get);
        var environment = owner.encode(candidate);
        assertThat(environment).containsEntry("POCKETHIVE_OUTPUTS_REDIS_PUSHDIRECTION", "LPUSH")
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_ROUTES_0_LIST", "${DESTINATION}");
        var properties = Map.of("pockethive.outputs.redis.source-step", "FIRST",
            "pockethive.outputs.redis.push-direction", "LPUSH",
            "pockethive.outputs.redis.routes[0].match", "x.*",
            "pockethive.outputs.redis.routes[0].list", "selected");
        var bootstrap = owner.resolve(Map.of("outputs", output), candidate, properties::get);
        var settings = (Map<?, ?>) ((Map<?, ?>) bootstrap.get("outputs")).get("redis");
        assertThat(settings.get("pushDirection")).isEqualTo("LPUSH");
        assertThat(settings.get("routes")).isEqualTo(List.of(Map.of("match", "x.*", "list", "selected")));
        assertThat(declared).containsEntry("pushDirection", "RPUSH")
            .containsEntry("routes", List.of(Map.of("match", "x.*", "list", "${DESTINATION}")));
    }

    @Test
    void encodingDoesNotEraseInvalidOriginalScalarTypesOrUnknownRouteFields() {
        for (Object route : List.of(Map.of("match", "x", "list", 42), Map.of("list", "out", "unknown", true))) {
            var fields = new LinkedHashMap<String, Object>(Map.of("host", "redis", "port", 6379,
                "ssl", false, "sourceStep", "FIRST", "pushDirection", "RPUSH", "maxLen", 0, "routes", List.of(route)));
            var output = Map.<String, Object>of("type", "REDIS", "redis", fields);
            var candidate = owner.candidate(output, ignored -> null);
            owner.encode(candidate);
            var properties = Map.of("pockethive.outputs.redis.source-step", "FIRST",
                "pockethive.outputs.redis.push-direction", "RPUSH",
                "pockethive.outputs.redis.routes[0].match", "x",
                "pockethive.outputs.redis.routes[0].list", "out");
            assertThatThrownBy(() -> owner.resolve(Map.of("outputs", output), candidate, properties::get))
                .isInstanceOf(WorkConfigurationException.class);
        }
    }
}
