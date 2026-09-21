package io.pockethive.rabbit.api;


import io.pockethive.work.config.WorkInputSettings;
import io.pockethive.rabbit.config.RabbitSettingValues;

/**
 * Responsibility: retain one resolved Rabbit Work input settings snapshot.
 * Must not: resolve connection credentials, select an input adapter or create a Rabbit consumer.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitInputSettings(String queue, int prefetch, int concurrentConsumers, boolean exclusive)
    implements WorkInputSettings {
    public static final int DEFAULT_PREFETCH = 50;
    public static final int DEFAULT_CONCURRENT_CONSUMERS = 1;
    public static final boolean DEFAULT_EXCLUSIVE = false;


    public RabbitInputSettings {
        queue = RabbitSettingValues.requiredText(queue);
        prefetch = RabbitSettingValues.positiveInteger(prefetch);
        concurrentConsumers = RabbitSettingValues.positiveInteger(concurrentConsumers);
        RabbitSettingValues.consumerPolicy(concurrentConsumers, exclusive);
    }
}
