package io.pockethive.rabbit.api;


import java.util.Optional;

/**
 * Responsibility: expose Rabbit resource operations with explicit outcomes and no client access.
 * Must not: decide domain topology, authorize cleanup or infer operation success for a caller.
 * Contract: RESP-RABBIT-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-rabbit-resources.
 */
public interface RabbitResources {
    void declareExchange(RabbitExchangeSpec exchange);
    void declareQueue(RabbitQueueSpec queue);
    void bind(RabbitBindingSpec binding);
    void unbind(RabbitBindingSpec binding);
    Optional<RabbitQueueObservation> queue(String name);
    boolean exchangeExists(String name);
    /** Returns only after queue absence is observed; failures propagate. */
    void deleteQueue(String name);
    /** Returns only after exchange absence is observed; failures propagate. */
    void deleteExchange(String name);
}
