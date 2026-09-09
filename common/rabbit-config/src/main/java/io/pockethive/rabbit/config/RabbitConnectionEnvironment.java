package io.pockethive.rabbit.config;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: map Rabbit connection values between resolved environment properties and container export.
 * Must not: repeat settings validation, infer defaults or encode Work/Control topology or delivery policy.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public final class RabbitConnectionEnvironment {

    private RabbitConnectionEnvironment() {
    }

    public static Map<String, String> encode(RabbitConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return Map.of(
            "SPRING_RABBITMQ_HOST", settings.host(),
            "SPRING_RABBITMQ_PORT", Integer.toString(settings.port()),
            "SPRING_RABBITMQ_USERNAME", settings.username(),
            "SPRING_RABBITMQ_PASSWORD", settings.password(),
            "SPRING_RABBITMQ_VIRTUAL_HOST", settings.virtualHost());
    }

    public static RabbitConnectionSettings decode(Function<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        String portText = properties.apply("spring.rabbitmq.port");
        int port;
        try {
            port = Integer.parseInt(portText == null ? null : portText.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("spring.rabbitmq.port must be a 32-bit integer");
        }
        return new RabbitConnectionSettings(
            properties.apply("spring.rabbitmq.host"), port,
            properties.apply("spring.rabbitmq.username"), properties.apply("spring.rabbitmq.password"),
            properties.apply("spring.rabbitmq.virtual-host"));
    }
}
