package io.pockethive.rabbit.api;

import java.util.Map;

/**
 * Responsibility: project debug tap TTL and capacity into Rabbit queue and binding specifications.
 * Must not: choose Work routes, manage tap lifecycle, validate request limits or access the broker.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/work-plane-boundaries.md#physical-resource-naming-transfer.
 */
public record RabbitDebugTapSpec(RabbitQueueSpec queue, RabbitBindingSpec binding) {
    private static final String MESSAGE_TTL = "x-message-ttl";
    private static final String MAX_LENGTH = "x-max-length";

    public static RabbitDebugTapSpec create(String name, String exchange, String routingKey,
                                            int ttlSeconds, int maxItems) {
        return new RabbitDebugTapSpec(
            new RabbitQueueSpec(name, false, true, true,
                Map.of(MESSAGE_TTL, ttlSeconds * 1000L, MAX_LENGTH, maxItems)),
            new RabbitBindingSpec(name, exchange, routingKey, Map.of()));
    }
}
