package io.pockethive.controlplane.messaging;

import io.pockethive.controlplane.codec.ControlPlaneCodec;
import org.springframework.amqp.core.AmqpTemplate;

import java.util.Objects;

/**
 * Spring AMQP backed publisher that converts and sends control-plane traffic.
 */
public final class AmqpControlPlanePublisher implements ControlPlanePublisher {

    private final AmqpTemplate template;
    private final String exchange;
    private final ControlPlaneCodec codec;

    public AmqpControlPlanePublisher(AmqpTemplate template, String exchange, ControlPlaneCodec codec) {
        this.template = Objects.requireNonNull(template, "template");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void publishSignal(SignalMessage message) {
        Objects.requireNonNull(message, "message");
        template.convertAndSend(exchange, message.routingKey(), codec.encode(message.payload(), message.routingKey()));
    }

    @Override
    public void publishEvent(EventMessage message) {
        Objects.requireNonNull(message, "message");
        template.convertAndSend(exchange, message.routingKey(), codec.encode(message.payload(), message.routingKey()));
    }
}
