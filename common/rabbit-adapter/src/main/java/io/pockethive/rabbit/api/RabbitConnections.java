package io.pockethive.rabbit.api;

import java.util.Objects;

/**
 * Responsibility: retain the explicitly configured Control and Work connections.
 * Must not: inherit settings between planes or create clients.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public record RabbitConnections(RabbitConnectionSettings control, RabbitConnectionSettings work) {
    public RabbitConnections {
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(work, "work");
    }
}
