package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.messaging.EventMessage;

import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.rabbit.api.RabbitPublisher;

import java.util.Objects;

/**
 * Spring AMQP backed publisher that converts and sends control-plane traffic.
 * <p>
 * Responsibility: publish canonical Control Plane messages through AMQP.
 * Must not: encode alternate envelopes or publish Work results.
 * Contract: RESP-CP-PUBLISH — docs/architecture/runtime-responsibilities.md#resp-cp-publish.
 */
public final class AmqpControlPlanePublisher implements ControlPlanePublisher {

    private final RabbitPublisher template;
    private final String exchange;
    private final ControlPlaneCodec codec;

    public AmqpControlPlanePublisher(RabbitPublisher template, String exchange, ControlPlaneCodec codec) {
        this.template = Objects.requireNonNull(template, "template");
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public void publishSignal(SignalMessage message) {
        Objects.requireNonNull(message, "message");
        template.sendText(exchange, message.routingKey(), codec.encode(message.payload(), message.routingKey()));
    }

    @Override
    public void publishEvent(EventMessage message) {
        Objects.requireNonNull(message, "message");
        template.sendText(exchange, message.routingKey(), codec.encode(message.payload(), message.routingKey()));
    }
}
