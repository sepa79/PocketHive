package io.pockethive.controlplane.messaging;

import org.springframework.amqp.core.AmqpTemplate;

import java.util.Objects;

/**
 * Spring AMQP backed publisher that converts and sends control-plane traffic.
 */
public final class AmqpControlPlanePublisher implements ControlPlanePublisher {

    private final AmqpTemplate template;
    private final String exchange;

    public AmqpControlPlanePublisher(AmqpTemplate template, String exchange) {
        this.template = Objects.requireNonNull(template, "template");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
    }

    @Override
    public void publishSignal(SignalMessage message) {
        Objects.requireNonNull(message, "message");
        template.convertAndSend(exchange, message.routingKey(), message.payload());
    }

    @Override
    public void publishEvent(EventMessage message) {
        Objects.requireNonNull(message, "message");
        template.convertAndSend(exchange, message.routingKey(), message.payload());
    }
}
