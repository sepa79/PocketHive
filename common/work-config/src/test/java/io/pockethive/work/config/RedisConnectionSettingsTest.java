package io.pockethive.work.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisConnectionSettingsTest {
    private final WorkConfigurationParser parser = new WorkConfigurationParser();

    @Test
    void resolvesConnectionWithoutChangingPasswordOrExposingCredentials() {
        var settings = parser.parseRedisConnection(" redis ", "6380", " user ", " secret ", "TRUE", "redis");
        assertThat(settings.host()).isEqualTo("redis");
        assertThat(settings.port()).isEqualTo(6380);
        assertThat(settings.ssl()).isTrue();
        assertThat(settings.username()).isEqualTo("user");
        assertThat(settings.password()).isEqualTo(" secret ");
        assertThat(settings.toString()).doesNotContain("secret", "user");
        assertThat(settings).isEqualTo(parser.parseRedisConnection("redis", 6380, "user", " secret ", true, "redis"));
        assertThat(parser.parseRedisConnection("redis", 6379, "user", "", false, "redis").password()).isEmpty();
    }

    @Test
    void rejectsMissingWrongTypeAndOutOfRangeValuesWithoutCoercion() {
        var invalid = parser.validateRedisConnection(7, 1.5, true, List.of("secret"), "yes", "redis", WorkConfigurationMode.RESOLVED);
        assertThat(invalid.settings()).isNull();
        assertThat(invalid.problems()).extracting(WorkConfigurationProblem::path)
            .containsExactly("redis.host", "redis.port", "redis.username", "redis.password", "redis.ssl");
        for (Object port : List.of(0, 65536, Double.NaN, "wrong")) {
            assertThatThrownBy(() -> parser.parseRedisConnection("redis", port, null, null, false, "redis"))
                .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("redis.port");
        }
        assertThatThrownBy(() -> parser.parseRedisConnection(" ", null, "user", null, null, "redis"))
            .hasMessageContaining("redis.host", "redis.port", "redis.password", "redis.ssl").hasMessageNotContaining("user;");
    }

    @Test
    void mergesMissingFieldsButRejectsExplicitNullForRequiredFields() {
        var base = parser.parseRedisConnection("redis", 6379, "user", "secret", false, "redis");
        var changed = parser.mergeRedisConnection(base, Map.of("ssl", true), "redis");
        assertThat(changed.password()).isEqualTo("secret");
        assertThat(changed.ssl()).isTrue();
        var patch = new LinkedHashMap<String, Object>();
        patch.put("port", null);
        assertThatThrownBy(() -> parser.mergeRedisConnection(base, patch, "redis")).hasMessageContaining("redis.port");
        assertThat(base.port()).isEqualTo(6379);
        patch.clear(); patch.put("username", null); patch.put("password", null);
        assertThat(parser.mergeRedisConnection(base, patch, "redis").password()).isNull();
    }

    @Test
    void defersAuthoringExpressionsAndRequiresResolvedRuntimeSettings() {
        var values = Map.of("host", "{{ 'redis' }}", "port", "{{ 6379 }}", "ssl", "{{ false }}");
        var result = parser.validateRedisConnection(values, "redis", WorkConfigurationMode.AUTHORING);
        assertThat(result.problems()).isEmpty();
        assertThat(result.settings()).isNull();
        assertThat(result.deferredPaths()).containsExactly("redis.host", "redis.port", "redis.ssl");
        assertThatThrownBy(() -> parser.parseRedisConnection(values, "redis")).hasMessageContaining("must be rendered");
        var mixed = parser.validateRedisConnection("{{ 'redis' }}", 0, null, null, false, "redis", WorkConfigurationMode.AUTHORING);
        assertThat(mixed.problems()).extracting(WorkConfigurationProblem::path).containsExactly("redis.port");
    }
}
