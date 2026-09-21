package io.pockethive.mcp.domain;

import java.time.Instant;

/**
 * Responsibility: Model the CapabilityFingerprint MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

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
