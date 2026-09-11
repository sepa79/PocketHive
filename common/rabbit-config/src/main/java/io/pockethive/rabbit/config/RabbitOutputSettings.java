package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkOutputSettings;
import java.util.Objects;

/**
 * Responsibility: retain one resolved Rabbit Work output settings snapshot.
 * Must not: resolve connection credentials, select an output adapter or publish to Rabbit.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitOutputSettings(String exchange, String routingKey, boolean persistent, boolean publisherConfirms)
    implements WorkOutputSettings {

    public RabbitOutputSettings {
        Objects.requireNonNull(exchange, "exchange");
        Objects.requireNonNull(routingKey, "routingKey");
        if (exchange.isBlank()) {
            throw new IllegalArgumentException("exchange must not be blank");
        }
        if (routingKey.isBlank()) {
            throw new IllegalArgumentException("routingKey must not be blank");
        }
    }
}
