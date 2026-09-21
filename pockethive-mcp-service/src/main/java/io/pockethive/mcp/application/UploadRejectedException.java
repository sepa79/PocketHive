package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit upload rejected application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class UploadRejectedException extends RuntimeException {
    public UploadRejectedException(String code) {
        super(code);
    }

    public UploadRejectedException(String code, Throwable cause) {
        super(code, cause);
    }
}
