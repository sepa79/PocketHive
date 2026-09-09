package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.rabbit.config.RabbitConnectionEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RabbitConnectionConfigurationTest {

    private final RabbitConnectionConfiguration configuration = new RabbitConnectionConfiguration();

    @Test
    void environmentExportRoundTripsThroughBootstrapDecoding() {
        var input = connectionEnvironment();
        var settings = configuration.rabbitConnectionSettings(environment(input));

        assertThat(RabbitConnectionEnvironment.encode(settings)).containsExactlyInAnyOrderEntriesOf(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOST", "PORT", "USERNAME", "PASSWORD", "VIRTUAL_HOST"})
    void rejectsMissingFieldInsteadOfUsingSpringClientDefaults(String suffix) {
        var input = connectionEnvironment();
        input.remove("SPRING_RABBITMQ_" + suffix);

        assertThatThrownBy(() -> configuration.rabbitConnectionSettings(environment(input)))
            .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAbsentConnectionBlock() {
        assertThatThrownBy(() -> configuration.rabbitConnectionSettings(new MockEnvironment()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq");
    }

    private static Map<String, String> connectionEnvironment() {
        return new LinkedHashMap<>(Map.of(
            "SPRING_RABBITMQ_HOST", "broker.example",
            "SPRING_RABBITMQ_PORT", "5678",
            "SPRING_RABBITMQ_USERNAME", "operator",
            "SPRING_RABBITMQ_PASSWORD", " secret ",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "/tenant"));
    }

    private static MockEnvironment environment(Map<String, String> values) {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "test-environment", new LinkedHashMap<>(values)));
        return environment;
    }
}
