package io.pockethive.mcp.application;

import java.util.Objects;

/**
 * Responsibility: Carry immutable issued upload capability application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record IssuedUploadCapability(String value, String digest) {
    public IssuedUploadCapability {
        Objects.requireNonNull(value);
        UploadCapabilityAuthority.requireDigest(digest);
    }
}
