package io.pockethive.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisConnectionEnvironmentCodecTest {
    @Test
    void encodesNumericValuesWithoutChangingStrings() {
        var fields = Map.of("host", " redis ", "port", 6380.0, "username", " user ", "password", " secret ", "ssl", "TRUE");
        assertThat(RedisConnectionEnvironmentCodec.input(fields)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "POCKETHIVE_INPUTS_REDIS_HOST", " redis ", "POCKETHIVE_INPUTS_REDIS_PORT", "6380",
            "POCKETHIVE_INPUTS_REDIS_SSL", "TRUE", "POCKETHIVE_INPUTS_REDIS_USERNAME", " user ",
            "POCKETHIVE_INPUTS_REDIS_PASSWORD", " secret "));
        assertThat(RedisConnectionEnvironmentCodec.output(fields)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "POCKETHIVE_OUTPUTS_REDIS_HOST", " redis ", "POCKETHIVE_OUTPUTS_REDIS_PORT", "6380",
            "POCKETHIVE_OUTPUTS_REDIS_SSL", "TRUE", "POCKETHIVE_OUTPUTS_REDIS_USERNAME", " user ",
            "POCKETHIVE_OUTPUTS_REDIS_PASSWORD", " secret "));
    }

    @Test
    void distinguishesAbsentCredentialsFromExplicitEmptyPassword() {
        var anonymous = Map.of("host", "redis", "port", 6379, "ssl", false);
        assertThat(RedisConnectionEnvironmentCodec.input(anonymous)).hasSize(3)
            .doesNotContainKeys("POCKETHIVE_INPUTS_REDIS_USERNAME", "POCKETHIVE_INPUTS_REDIS_PASSWORD");
        var empty = Map.of("host", "redis", "port", 6379, "username", "user", "password", "", "ssl", false);
        assertThat(RedisConnectionEnvironmentCodec.output(empty))
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_USERNAME", "user")
            .containsEntry("POCKETHIVE_OUTPUTS_REDIS_PASSWORD", "");
    }

    @Test
    void rejectsUnexportableValuesWithoutExposingThem() {
        for (Object invalid : java.util.List.of(Double.NaN, Map.of("secret", "synthetic-secret"))) {
            assertThatThrownBy(() -> RedisConnectionEnvironmentCodec.input(Map.of("password", invalid)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("password")
                .hasMessageNotContaining("synthetic-secret").hasNoCause();
        }
    }
}
