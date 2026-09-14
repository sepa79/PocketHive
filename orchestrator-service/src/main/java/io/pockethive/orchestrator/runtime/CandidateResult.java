package io.pockethive.orchestrator.runtime;

import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Objects;

/**
 * Responsibility: carry the CandidateResult contract with explicit resource plane.
 * Must not: infer plane from resource names or bypass the owning resource operation.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record CandidateResult(
    String candidateId,
    RuntimeCleanupAction action,
    String resourceId,
    RuntimeCleanupStatus status,
    String correlationId,
    String operationUrl,
    String error,
    ResourcePlane plane) {
    public CandidateResult {
        Objects.requireNonNull(plane, "plane");
    }

}
