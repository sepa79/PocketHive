package io.pockethive.controlplane.messaging;

import io.pockethive.control.ControlPlaneEnvelope;
import java.util.Objects;

/**
 * Describes a control signal payload and the routing key used to publish it.
 */
public record SignalMessage(String routingKey, ControlPlaneEnvelope payload) {

    public SignalMessage {
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
    }
}
