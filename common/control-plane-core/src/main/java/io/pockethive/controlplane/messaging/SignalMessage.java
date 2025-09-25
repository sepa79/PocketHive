package io.pockethive.controlplane.messaging;

import java.util.Objects;

/**
 * Describes a control signal payload and the routing key used to publish it.
 */
public record SignalMessage(String routingKey, Object payload) {

    public SignalMessage {
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
    }
}
