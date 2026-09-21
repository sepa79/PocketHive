package io.pockethive.rabbit.api;


import io.pockethive.work.config.WorkOutputSettings;
import io.pockethive.rabbit.config.RabbitSettingValues;

/**
 * Responsibility: retain one resolved Rabbit Work output settings snapshot.
 * Must not: resolve connection credentials, select an output adapter or publish to Rabbit.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitOutputSettings(String exchange, String routingKey, boolean persistent, boolean publisherConfirms)
    implements WorkOutputSettings {
    public static final boolean DEFAULT_PERSISTENT = true;
    public static final boolean DEFAULT_PUBLISHER_CONFIRMS = false;


    public RabbitOutputSettings {
        exchange = RabbitSettingValues.requiredText(exchange);
        routingKey = RabbitSettingValues.requiredText(routingKey);
    }
}
