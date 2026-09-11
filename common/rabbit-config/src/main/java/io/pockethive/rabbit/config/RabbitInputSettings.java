package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkInputSettings;
import java.util.Objects;

/**
 * Responsibility: retain one resolved Rabbit Work input settings snapshot.
 * Must not: resolve connection credentials, select an input adapter or create a Rabbit consumer.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public record RabbitInputSettings(String queue, int prefetch, int concurrentConsumers, boolean exclusive)
    implements WorkInputSettings {

    public RabbitInputSettings {
        Objects.requireNonNull(queue, "queue");
        if (queue.isBlank()) {
            throw new IllegalArgumentException("queue must not be blank");
        }
        if (prefetch < 1) {
            throw new IllegalArgumentException("prefetch must be positive");
        }
        if (concurrentConsumers < 1) {
            throw new IllegalArgumentException("concurrentConsumers must be positive");
        }
    }
}
