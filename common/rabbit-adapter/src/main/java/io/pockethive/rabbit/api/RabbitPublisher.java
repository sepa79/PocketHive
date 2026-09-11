package io.pockethive.rabbit.api;


/**
 * Responsibility: submit an explicitly addressed message.
 * Must not: encode domain envelopes, select routes or claim broker confirmation from submission.
 * Contract: docs/architecture/work-plane-boundaries.md#5-delivery-and-failure-decisions.
 */
public interface RabbitPublisher {
    void send(String exchange, String routingKey, RabbitMessage message);
    default void sendText(String exchange, String routingKey, String payload) {
        send(exchange, routingKey, RabbitMessage.text(payload));
    }
}
