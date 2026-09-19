package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitConnectionEnvironment;
import io.pockethive.rabbit.api.RabbitConnectionSettings;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
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

    @Test
    void rejectsControlEnvironmentAliasesWithoutReadingTheirValues() {
        for (String key : java.util.List.of("SPRING_RABBITMQ_HOST", "SPRING_RABBITMQ_VIRTUALHOST",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "spring.rabbitmq.virtual-host", "spring.rabbitmq.virtualHost",
            "SPRING_RABBITMQ_ADDRESSES", "SPRING_RABBITMQ_SSL_ENABLED")) {
            assertThat(RabbitConnectionEnvironment.controlOverrideProblems(Map.of(key, "${SECRET}")))
                .singleElement().satisfies(problem -> {
                    assertThat(problem.path()).startsWith("spring.rabbitmq.");
                    assertThat(problem.message()).contains("owned by the provisioning environment").doesNotContain("SECRET");
                });
        }
        assertThat(RabbitConnectionEnvironment.controlOverrideProblems(Map.of("WIZARD_PROOF_CLIENT_SECRET", "dummy",
            "JAVA_TOOL_OPTIONS", "-XX:ThisOptionDoesNotExist"))).isEmpty();
    }

    @Test
    void decodesRequiredFieldsThroughTheSettingsContract() {
        Map<String, String> properties = new LinkedHashMap<>(Map.of(
            "spring.rabbitmq.host", "broker", "spring.rabbitmq.port", " 5673 ",
            "spring.rabbitmq.username", " user ", "spring.rabbitmq.password", " secret ",
            "spring.rabbitmq.virtual-host", "/tenant"));
        assertThat(RabbitConnectionEnvironment.decode(properties::get))
            .isEqualTo(new RabbitConnectionSettings("broker", 5673, " user ", " secret ", "/tenant"));
        for (String invalid : new String[] {"0", "65536", "1.5", "2147483648", ""}) {
            properties.put("spring.rabbitmq.port", invalid);
            assertThatThrownBy(() -> RabbitConnectionEnvironment.decode(properties::get))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.port");
        }
        properties.put("spring.rabbitmq.port", "5673");
        properties.put("spring.rabbitmq.password", "");
        assertThatThrownBy(() -> RabbitConnectionEnvironment.decode(properties::get))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.rabbitmq.password");
    }
    @Test
    void rejectsPerWorkerWorkBrokerOverrides() {
        for (String field : java.util.List.of("host", "port", "username", "password", "virtual-host")) {
            String key = "pockethive.rabbit.work." + field;
            assertThat(RabbitConnectionEnvironment.workOverrideProblems(path -> path.equals(key) ? "override" : null))
                .singleElement().satisfies(problem -> assertThat(problem.path()).isEqualTo(key));
        }
    }

}
