package io.pockethive.mcp.application;

/**
 * Responsibility: Represent the explicit tool execution application failure.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class ToolExecutionException extends RuntimeException {
    private final String code;

    public ToolExecutionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
