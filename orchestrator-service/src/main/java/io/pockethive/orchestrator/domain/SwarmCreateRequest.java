package io.pockethive.orchestrator.domain;

/**
 * Payload for sig.swarm-create carrying a template reference.
 */
public record SwarmCreateRequest(String templateId, String idempotencyKey, String notes) {
    public SwarmCreateRequest {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must be provided");
        }
    }
}
