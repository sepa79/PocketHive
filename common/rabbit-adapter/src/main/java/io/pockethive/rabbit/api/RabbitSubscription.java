package io.pockethive.rabbit.api;


import java.util.Objects;

/**
 * Responsibility: carry validated consumer settings and explicit startup intent to Rabbit.
 * Must not: define adapter defaults, construct names or own worker enablement.
 * Contract: docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public record RabbitSubscription(String id, String queue, int prefetch, int concurrentConsumers,
                                 boolean exclusive, boolean autoStartup) {
    public RabbitSubscription {
        Objects.requireNonNull(id, "id");
        queue = new RabbitInputSettings(queue, prefetch, concurrentConsumers, exclusive).queue();
    }
}
