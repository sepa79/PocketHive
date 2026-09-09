package io.pockethive.rabbit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RabbitConnectionEnvironmentTest {

    @Test
    void exportsExactlyTheDeclaredConnectionWithoutNormalizingCredentials() {
        var settings = new RabbitConnectionSettings("broker.example", 65535, " user ", " password ", "/tenant/work");

        assertThat(RabbitConnectionEnvironment.encode(settings)).containsExactlyInAnyOrderEntriesOf(Map.of(
            "SPRING_RABBITMQ_HOST", "broker.example",
            "SPRING_RABBITMQ_PORT", "65535",
            "SPRING_RABBITMQ_USERNAME", " user ",
            "SPRING_RABBITMQ_PASSWORD", " password ",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "/tenant/work"));
    }
}
