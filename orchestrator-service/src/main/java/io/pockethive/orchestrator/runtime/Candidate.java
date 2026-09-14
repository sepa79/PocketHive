package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: carry the Candidate contract with explicit resource plane.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record Candidate(
    String candidateId,
    RuntimeCleanupAction action,
    String resourceId,
    String resourceType,
    String resourceKind,
    String role,
    String instance,
    String state,
    String image,
    Long queueDepth,
    Integer consumers,
    boolean running,
    boolean highRisk,
    String reason,
    Map<String, String> labels,
    ResourcePlane plane) {
    public Candidate {
        Objects.requireNonNull(plane, "plane");
    }

}
