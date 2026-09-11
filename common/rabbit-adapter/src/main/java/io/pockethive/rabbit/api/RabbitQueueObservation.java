package io.pockethive.rabbit.api;


import java.util.OptionalLong;

/**
 * Responsibility: project broker-reported queue counts and optional oldest-message age.
 * Must not: infer readiness, desired state or operation completion.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public record RabbitQueueObservation(long messages, int consumers, OptionalLong oldestAgeSeconds) {
    public RabbitQueueObservation {
        if (messages < 0 || consumers < 0) throw new IllegalArgumentException("Queue counts must not be negative");
        java.util.Objects.requireNonNull(oldestAgeSeconds, "oldestAgeSeconds");
    }
}
