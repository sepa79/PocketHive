package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitMessage;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Responsibility: map Rabbit API message values to and from Spring AMQP messages.
 * Must not: encode domain payloads, resolve configuration or decide settlement.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
final class RabbitMessages {
    private RabbitMessages() { }
    static Message outbound(RabbitMessage source) {
        var properties = new MessageProperties();
        properties.setContentType(source.contentType());
        properties.setContentEncoding(source.contentEncoding());
        properties.setDeliveryMode(source.persistent() ? MessageDeliveryMode.PERSISTENT : MessageDeliveryMode.NON_PERSISTENT);
        properties.setHeaders(source.headers());
        byte[] body = source.body();
        properties.setContentLength(body.length);
        return new Message(body, properties);
    }
    static RabbitMessage inbound(Message source) {
        var properties = source.getMessageProperties();
        return new RabbitMessage(source.getBody(), properties.getHeaders(), properties.getContentType(),
            properties.getContentEncoding(), properties.getReceivedDeliveryMode() == MessageDeliveryMode.PERSISTENT,
            properties.getReceivedRoutingKey());
    }
}
