package io.pockethive.mcp.application;

import java.util.Objects;

/**
 * Responsibility: Carry immutable prepared upload application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record PreparedUpload<T extends BundleUploadTicket>(T ticket, String uploadCapability) {
    public PreparedUpload {
        Objects.requireNonNull(ticket);
        if (uploadCapability == null || uploadCapability.isBlank()) {
            throw new IllegalArgumentException("UPLOAD_CAPABILITY_REQUIRED");
        }
    }
}
