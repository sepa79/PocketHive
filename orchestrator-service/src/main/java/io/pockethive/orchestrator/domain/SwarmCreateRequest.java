package io.pockethive.orchestrator.domain;

/**
 * REST payload for swarm creation carrying a template reference.
 */
public record SwarmCreateRequest(String templateId,
                                 String idempotencyKey,
                                 String notes,
                                 Boolean autoPullImages,
                                 String sutId,
                                 String variablesProfileId) {
    public SwarmCreateRequest {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must be provided");
        }
    }

    public SwarmCreateRequest(String templateId, String idempotencyKey, String notes) {
        this(templateId, idempotencyKey, notes, null, null, null);
    }
}
