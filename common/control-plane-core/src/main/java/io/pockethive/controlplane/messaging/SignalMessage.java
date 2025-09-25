package io.pockethive.controlplane.messaging;

import io.pockethive.control.ControlSignal;
import java.util.Objects;

/**
 * Describes a control signal and the routing key used to publish it.
 */
public record SignalMessage(String routingKey, ControlSignal payload) {

    public SignalMessage {
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
    }
}
