package io.pockethive.worker.sdk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.config.WorkConfigurationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisSequenceConfigurationTest {
    @Test
    void appliesValidUpdatesAndRejectsInvalidValuesWithoutChangingTheConnection() {
        try (var sequences = new RedisSequenceConfiguration(new RedisSequenceProperties())) {
            sequences.configureFromWorkerConfig(Map.of("redis", Map.of("port", "6380", "ssl", true)));
            var updated = sequences.currentSettings();
            assertThat(updated.port()).isEqualTo(6380);
            assertThat(updated.ssl()).isTrue();
            for (var invalid : List.of(Map.of("port", 0), Map.of("port", "bad"), Map.of("ssl", "yes"), Map.of("host", " "))) {
                assertThatThrownBy(() -> sequences.configureFromWorkerConfig(Map.of("redis", invalid)))
                    .isInstanceOf(WorkConfigurationException.class);
                assertThat(sequences.currentSettings()).isEqualTo(updated);
            }

        }
    }
    @Test
    void disabledStartupKeepsExistingDefaultSettingsAndAcceptsLaterExplicitUpdates() {
        var properties = new RedisSequenceProperties();
        properties.setEnabled(false);
        properties.setHost("ignored-startup");
        properties.setPort(1);
        try (var sequences = new RedisSequenceConfiguration(properties)) {
            assertThat(sequences.currentSettings()).isEqualTo(new RedisSequenceProperties().connectionSettings("redis"));
            sequences.configureFromWorkerConfig(Map.of("redis", Map.of("host", "configured", "port", 6380)));
            assertThat(sequences.currentSettings().host()).isEqualTo("configured");
            assertThat(sequences.currentSettings().port()).isEqualTo(6380);
        }
    }
    @Test
    void instancesKeepIndependentSelectionAndInvalidFormatDoesNotConsumeCounter() {
        String host = System.getenv("AUTH_REDIS_TEST_HOST");
        org.junit.jupiter.api.Assumptions.assumeTrue(host != null, "explicit Redis fixture required");
        var properties = new RedisSequenceProperties();
        properties.setHost(host);
        properties.setPort(Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")));
        String key = "extraction-" + java.util.UUID.randomUUID();
        try (var first = new RedisSequenceConfiguration(properties);
             var second = new RedisSequenceConfiguration(properties)) {
            first.configureFromWorkerConfig(Map.of("redis", Map.of("port", 1)));
            assertThat(second.currentSettings().port()).isEqualTo(properties.getPort());
            try {
                assertThatThrownBy(() -> second.next(key, "NUMERIC", "invalid", 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
                assertThat(second.next(key, "NUMERIC", "%02d", 1, 0)).isEqualTo("00");
                assertThat(second.next(key, "NUMERIC", "%02d", 1, 0)).isEqualTo("01");
                assertThat(second.reset(key)).isTrue();
                assertThat(second.reset(key)).isFalse();
                assertThat(second.next(key, "NUMERIC", "%02d", 1, 0)).isEqualTo("00");
            } finally {
                second.reset(key);
            }
        }
    }

}
