package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Objects;

/**
 * Responsibility: carry the RabbitQueueSnapshot contract with explicit resource plane.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: docs/architecture/work-plane-boundaries.md#connection-split-prerequisite-resource-identity.
 */
public record RabbitQueueSnapshot(
    String name,
    boolean present,
    Long messages,
    Integer consumers,
    String state,
    Boolean durable,
    Boolean autoDelete,
    Boolean diagnosticOnly,
    String reason,
    ResourcePlane plane) {
    public RabbitQueueSnapshot {
        Objects.requireNonNull(plane, "plane");
        plane.requireRabbit();
    }

}
