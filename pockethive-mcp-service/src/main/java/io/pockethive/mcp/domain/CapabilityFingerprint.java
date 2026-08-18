package io.pockethive.mcp.domain;

import java.time.Instant;

public record CapabilityFingerprint(String digest, Instant observedAt) {
    public CapabilityFingerprint {
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException("digest must not be blank");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        digest = digest.trim();
    }
}
