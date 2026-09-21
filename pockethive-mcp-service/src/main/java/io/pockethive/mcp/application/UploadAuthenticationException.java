package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit upload authentication application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class UploadAuthenticationException extends RuntimeException {
    public UploadAuthenticationException(String code) {
        super(code);
    }
}
