package io.pockethive.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.rabbit.api.RabbitConnectionEnvironment;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class RabbitConnectionConfigurationTest {

    @Test
    void environmentExportRoundTripsThroughBootstrapDecoding() {
        var input = connectionEnvironment();
        var settings = RabbitConnectionEnvironment.decodeConnections(environment(input)::getProperty);

        assertThat(RabbitConnectionEnvironment.encode(settings)).containsExactlyInAnyOrderEntriesOf(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOST", "PORT", "USERNAME", "PASSWORD", "VIRTUAL_HOST"})
    void rejectsMissingFieldInsteadOfUsingSpringClientDefaults(String suffix) {
        var input = connectionEnvironment();
        input.remove("SPRING_RABBITMQ_" + suffix);

        assertThatThrownBy(() -> RabbitConnectionEnvironment.decodeConnections(environment(input)::getProperty))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsAbsentConnectionBlock() {
        assertThatThrownBy(() -> RabbitConnectionEnvironment.decodeConnections(new MockEnvironment()::getProperty))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq");
    }

    @Test
    void refusesToInheritMissingWorkSettingsFromControl() {
        var input = connectionEnvironment();
        input.remove("POCKETHIVE_RABBIT_WORK_HOST");
        assertThatThrownBy(() -> RabbitConnectionEnvironment.decodeConnections(environment(input)::getProperty))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bootControlClientUsesTheSameValuesThatAreExported() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withUserConfiguration(io.pockethive.rabbit.config.RabbitWorkConnectionConfiguration.class)
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.class,
                RabbitConnectionConfiguration.class))
            .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-env", new LinkedHashMap<>(connectionEnvironment()))))
            .run(context -> {
                assertThat(context).hasNotFailed();
                var settings = context.getBean(io.pockethive.rabbit.api.RabbitConnections.class);
                var client = context.getBean(org.springframework.amqp.rabbit.connection.CachingConnectionFactory.class);
                assertThat(client.getHost()).isEqualTo(settings.control().host());
                assertThat(client.getPort()).isEqualTo(settings.control().port());
                assertThat(client.getVirtualHost()).isEqualTo(settings.control().virtualHost());
                assertThat(client.getRabbitConnectionFactory().getUsername()).isEqualTo(settings.control().username());
                assertThat(client.getRabbitConnectionFactory().getPassword()).isEqualTo(settings.control().password());
            });
    }

    private static Map<String, String> connectionEnvironment() {
        var values = new LinkedHashMap<>(Map.of(
            "SPRING_RABBITMQ_HOST", "control.example", "SPRING_RABBITMQ_PORT", "5678",
            "SPRING_RABBITMQ_USERNAME", "operator", "SPRING_RABBITMQ_PASSWORD", " secret ",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "/control"));
        values.putAll(Map.of("POCKETHIVE_RABBIT_WORK_HOST", "work.example", "POCKETHIVE_RABBIT_WORK_PORT", "5679",
            "POCKETHIVE_RABBIT_WORK_USERNAME", "worker", "POCKETHIVE_RABBIT_WORK_PASSWORD", "work secret",
            "POCKETHIVE_RABBIT_WORK_VIRTUAL_HOST", "/work"));
        return values;
    }

    private static MockEnvironment environment(Map<String, String> values) {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "test-environment", new LinkedHashMap<>(values)));
        return environment;
    }
}
