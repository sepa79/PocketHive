package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit owner call rejected application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class OwnerCallRejectedException extends RuntimeException {
    public OwnerCallRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
