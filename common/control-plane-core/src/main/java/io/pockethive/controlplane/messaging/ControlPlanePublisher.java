package io.pockethive.controlplane.messaging;

/**
 * Publishes control-plane traffic to the underlying AMQP exchange.
 */
public interface ControlPlanePublisher {

    void publishSignal(SignalMessage message);

    void publishEvent(EventMessage message);
}
