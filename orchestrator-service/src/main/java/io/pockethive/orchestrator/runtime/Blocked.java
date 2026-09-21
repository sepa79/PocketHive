package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: carry the Blocked contract with explicit resource plane.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record Blocked(
    String candidateId,
    RuntimeCleanupAction action,
    String resourceId,
    String resourceType,
    String reason,
    Map<String, String> labels,
    ResourcePlane plane) {
    public Blocked {
        Objects.requireNonNull(plane, "plane");
    }

}
