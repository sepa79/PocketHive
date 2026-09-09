package io.pockethive.rabbit.config;

import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: encode validated Rabbit connection values into the container environment contract.
 * Must not: validate a second time, infer settings or encode Work/Control topology or delivery policy.
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
}
