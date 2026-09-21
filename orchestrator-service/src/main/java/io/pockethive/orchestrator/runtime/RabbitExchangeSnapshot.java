package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Objects;

/**
 * Responsibility: carry the RabbitExchangeSnapshot contract with explicit resource plane.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record RabbitExchangeSnapshot(
    String name,
    boolean present,
    String type,
    Boolean durable,
    Boolean autoDelete,
    String reason,
    ResourcePlane plane) {
    public RabbitExchangeSnapshot {
        Objects.requireNonNull(plane, "plane");
        plane.requireRabbit();
    }

}
