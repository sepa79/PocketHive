package io.pockethive.rabbit.api;


import java.util.Optional;

/**
 * Responsibility: receive and settle at most one message for explicit polling consumers.
 * Must not: interpret payloads, create queues or convert broker failures to empty results.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
public interface RabbitReceiver {
    Optional<RabbitMessage> receive(String queue);
}
