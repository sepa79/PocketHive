package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.NetworkMode;

/**
 * REST payload for swarm creation carrying a template reference.
 */
public record SwarmCreateRequest(String templateId,
                                 String idempotencyKey,
                                 String notes,
                                 Boolean autoPullImages,
                                 String sutId,
                                 String variablesProfileId,
                                 NetworkMode networkMode,
                                 String networkProfileId) {
    public SwarmCreateRequest {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must be provided");
        }
        templateId = templateId.trim();
        idempotencyKey = trimToNull(idempotencyKey);
        notes = trimToNull(notes);
        sutId = trimToNull(sutId);
        variablesProfileId = trimToNull(variablesProfileId);
        networkMode = NetworkMode.directIfNull(networkMode);
        networkProfileId = trimToNull(networkProfileId);
        if (networkMode == NetworkMode.DIRECT && networkProfileId != null) {
            throw new IllegalArgumentException("networkProfileId requires networkMode=PROXIED");
        }
        if (networkMode == NetworkMode.PROXIED && networkProfileId == null) {
            throw new IllegalArgumentException("networkProfileId must be provided when networkMode=PROXIED");
        }
        if (networkMode == NetworkMode.PROXIED && sutId == null) {
            throw new IllegalArgumentException("sutId must be provided when networkMode=PROXIED");
        }
    }

    public SwarmCreateRequest(String templateId, String idempotencyKey, String notes) {
        this(templateId, idempotencyKey, notes, null, null, null, NetworkMode.DIRECT, null);
    }

    public SwarmCreateRequest(String templateId,
                              String idempotencyKey,
                              String notes,
                              Boolean autoPullImages,
                              String sutId,
                              String variablesProfileId) {
        this(templateId, idempotencyKey, notes, autoPullImages, sutId, variablesProfileId, NetworkMode.DIRECT, null);
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
