package io.pockethive.rabbit.api;


import java.util.function.Consumer;

/**
 * Responsibility: register subscriptions and operate their listener lifecycle through Rabbit's API.
 * Must not: expose containers, interpret messages or decide worker desired state.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public interface RabbitListeners {
    void register(RabbitListenerBinding binding);
    /** Preserves callback-return acknowledgement semantics. */
    void register(RabbitSubscription subscription, Consumer<RabbitMessage> handler);
    RabbitListenerState state(String id);
    void start(String id);
    void stop(String id);
}
