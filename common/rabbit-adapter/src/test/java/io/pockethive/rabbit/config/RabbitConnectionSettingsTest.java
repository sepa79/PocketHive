package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitConnectionSettings;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RabbitConnectionSettingsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsMissingConnectionText(String invalid) {
        assertThatThrownBy(() -> new RabbitConnectionSettings(invalid, 5672, "user", "secret", "/"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.host");
        assertThatThrownBy(() -> new RabbitConnectionSettings("broker", 5672, invalid, "secret", "/"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.username");
        assertThatThrownBy(() -> new RabbitConnectionSettings("broker", 5672, "user", invalid, "/"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.password");
        assertThatThrownBy(() -> new RabbitConnectionSettings("broker", 5672, "user", "secret", invalid))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.virtual-host");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536})
    void rejectsInvalidPort(int port) {
        assertThatThrownBy(() -> new RabbitConnectionSettings("broker", port, "user", "secret", "/"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.port");
    }

    @Test
    void hidesConnectionValuesInText() {
        assertThat(new RabbitConnectionSettings("broker", 5672, "operator", "private-password", "/tenant").toString())
            .doesNotContain("broker", "operator", "private-password", "/tenant");
    }
}
