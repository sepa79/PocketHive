package io.pockethive.controlplane.messaging;

import io.pockethive.control.ControlPlaneEnvelope;
import java.util.Objects;

/**
 * Describes a control-plane event payload that should be published on an exchange.
 */
public record EventMessage(String routingKey, ControlPlaneEnvelope payload) {

    public EventMessage {
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
    }
}
