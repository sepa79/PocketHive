package io.pockethive.rabbit.api;


/**
 * Responsibility: submit an explicitly addressed message.
 * Must not: encode domain envelopes, select routes or claim broker confirmation from submission.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
public interface RabbitPublisher {
    void send(String exchange, String routingKey, RabbitMessage message);
    default void sendText(String exchange, String routingKey, String payload) {
        send(exchange, routingKey, RabbitMessage.text(payload));
    }
}
