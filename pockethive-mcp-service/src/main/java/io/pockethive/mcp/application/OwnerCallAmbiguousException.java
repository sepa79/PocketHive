package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit owner call ambiguous application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class OwnerCallAmbiguousException extends RuntimeException {
    public OwnerCallAmbiguousException(String message) {
        super(message);
    }

    public OwnerCallAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }
}
