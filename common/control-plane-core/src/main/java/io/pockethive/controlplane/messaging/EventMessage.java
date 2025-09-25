package io.pockethive.controlplane.messaging;

import java.util.Objects;

/**
 * Describes a control-plane event payload that should be published on an exchange.
 */
public record EventMessage(String routingKey, Object payload) {

    public EventMessage {
        Objects.requireNonNull(routingKey, "routingKey");
        Objects.requireNonNull(payload, "payload");
    }
}
