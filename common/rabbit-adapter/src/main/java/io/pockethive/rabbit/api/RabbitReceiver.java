package io.pockethive.rabbit.api;


import java.util.Optional;

/**
 * Responsibility: receive and settle at most one message for explicit polling consumers.
 * Must not: interpret payloads, create queues or convert broker failures to empty results.
 * Contract: docs/architecture/work-plane-boundaries.md#5-delivery-and-failure-decisions.
 */
public interface RabbitReceiver {
    Optional<RabbitMessage> receive(String queue);
}
