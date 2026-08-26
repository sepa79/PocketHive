package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit archive rejected application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class ArchiveRejectedException extends RuntimeException {
    public ArchiveRejectedException(String code) {
        super(code);
    }

    public ArchiveRejectedException(String code, Throwable cause) {
        super(code, cause);
    }
}
