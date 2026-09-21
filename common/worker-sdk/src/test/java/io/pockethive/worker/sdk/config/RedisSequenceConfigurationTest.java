package io.pockethive.worker.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.templating.RedisSequenceGenerator;
import io.pockethive.work.config.WorkConfigurationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisSequenceConfigurationTest {
    @Test
    void appliesValidUpdatesAndRejectsInvalidValuesWithoutChangingTheConnection() {
        var original = RedisSequenceGenerator.currentConfig();
        try {
            RedisSequenceConfiguration.configureFromWorkerConfig(Map.of("redis", Map.of("port", "6380", "ssl", true)));
            var updated = RedisSequenceGenerator.currentConfig();
            assertThat(updated.port()).isEqualTo(6380);
            assertThat(updated.ssl()).isTrue();
            for (var invalid : List.of(Map.of("port", 0), Map.of("port", "bad"), Map.of("ssl", "yes"), Map.of("host", " "))) {
                assertThatThrownBy(() -> RedisSequenceConfiguration.configureFromWorkerConfig(Map.of("redis", invalid)))
                    .isInstanceOf(WorkConfigurationException.class);
                assertThat(RedisSequenceGenerator.currentConfig()).isEqualTo(updated);
            }
        } finally {
            RedisSequenceGenerator.configure(original);
        }
    }
}
