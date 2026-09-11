package io.pockethive.swarmcontroller.runtime.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkConnectionEnvironmentResolverTest {
    private final WorkConnectionEnvironmentResolver resolver = new WorkConnectionEnvironmentResolver();

    @Test
    void resolvesBothDirectionsWithoutMutatingSourceOrLosingOtherSettings() {
        Map<String, Object> connection = Map.of("host", "base", "port", 6379, "ssl", false,
            "username", "user", "password", "secret", "defaultList", "retained");
        Map<String, Object> config = Map.of("inputs", Map.of("redis", connection),
            "outputs", Map.of("redis", connection), "enabled", false);
        var properties = rabbitProperties();
        properties.putAll(Map.of("pockethive.inputs.redis.port", "6380",
            "pockethive.inputs.redis.username", "", "pockethive.inputs.redis.password", "",
            "pockethive.outputs.redis.host", " output ", "pockethive.outputs.redis.ssl", "true"));

        var result = resolver.resolve(config, Map.of("CUSTOM", "retained"), properties::get, env -> properties::get);

        assertThat(result.environment()).containsEntry("CUSTOM", "retained")
            .containsEntry("POCKETHIVE_INPUTS_REDIS_PORT", "6380")
            .containsEntry("POCKETHIVE_INPUTS_REDIS_PASSWORD", "")
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_HOST", " output ")
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PORT", "6379")
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_SSL", "true");
        assertThat(redis(result.bootstrapConfig(), "inputs")).containsEntry("port", 6380)
            .containsEntry("password", "").containsEntry("defaultList", "retained").doesNotContainKey("username");
        assertThat(redis(result.bootstrapConfig(), "outputs")).containsEntry("host", "output")
            .containsEntry("ssl", true).containsEntry("password", "secret");
        assertThat(config.get("inputs")).isEqualTo(Map.of("redis", connection));
        assertThat(result.toString()).doesNotContain("secret");
    }

    @Test
    void rejectsExplicitInvalidOverridesWithoutPublishingPartialCandidates() {
        Map<String, Object> config = Map.of("outputs", Map.of("redis",
            Map.of("host", "base", "port", 6379, "ssl", false)));
        for (var invalid : Map.of("host", "", "port", "0", "ssl", "yes").entrySet()) {
            var properties = rabbitProperties();
            properties.put("pockethive.outputs.redis." + invalid.getKey(), invalid.getValue());
            Map<String, String> environment = new LinkedHashMap<>(Map.of("CUSTOM", "retained"));
            assertThatThrownBy(() -> resolver.resolve(config, environment, properties::get, env -> properties::get))
                .isInstanceOf(WorkConfigurationException.class)
                .hasMessageContaining("outputs.redis." + invalid.getKey());
            assertThat(environment).containsExactlyEntriesOf(Map.of("CUSTOM", "retained"));
            assertThat(redis(config, "outputs")).containsEntry("port", 6379);
        }
    }

    @Test
    void requiresConnectionForSelectedRedisAndAcceptsCompleteEnvironmentOnlyConnection() {
        var properties = rabbitProperties();
        assertThat(resolver.resolve(Map.of(), Map.of(), properties::get, env -> properties::get).bootstrapConfig()).isEmpty();
        properties.put("pockethive.outputs.type", "REDIS");
        assertThatThrownBy(() -> resolver.resolve(Map.of(), Map.of(), properties::get, env -> properties::get))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("outputs.redis.host");
        properties.putAll(Map.of("pockethive.outputs.redis.host", "redis",
            "pockethive.outputs.redis.port", "6381", "pockethive.outputs.redis.ssl", "false"));
        var result = resolver.resolve(Map.of(), Map.of(), properties::get, env -> properties::get);
        assertThat(redis(result.bootstrapConfig(), "outputs"))
            .containsExactlyInAnyOrderEntriesOf(Map.of("host", "redis", "port", 6381, "ssl", false));
    }

    @Test
    void preservesDeclaredTypesUntilExplicitOverridesReplaceThem() {
        var properties = rabbitProperties();
        var config = Map.<String, Object>of("outputs", Map.of("redis",
            Map.of("host", 7, "port", 6379, "ssl", false)));
        assertThatThrownBy(() -> resolver.resolve(config, Map.of(), properties::get, env -> properties::get))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("outputs.redis.host: Must be text");

        properties.put("pockethive.outputs.redis.host", "7");
        var result = resolver.resolve(config, Map.of(), properties::get, env -> properties::get);
        assertThat(redis(result.bootstrapConfig(), "outputs")).containsEntry("host", "7");
    }

    private static Map<String, String> rabbitProperties() {
        return new LinkedHashMap<>(Map.of("spring.rabbitmq.host", "rabbit", "spring.rabbitmq.port", "5672",
            "spring.rabbitmq.username", "user", "spring.rabbitmq.password", "secret",
            "spring.rabbitmq.virtual-host", "/"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> redis(Map<String, Object> config, String root) {
        return (Map<String, Object>) ((Map<?, ?>) config.get(root)).get("redis");
    }
}
