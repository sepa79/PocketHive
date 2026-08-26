package io.pockethive.mcp.adapter.mcp;

/**
 * Responsibility: Signal a protocol-safe MCP tool execution failure to the transport adapter.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes.
 * Contract: docs/mcp/README.md.
 */

final class ToolProtocolException extends RuntimeException {
    ToolProtocolException(String correlationId, Throwable cause) {
        super("Unexpected tool failure; correlationId=" + correlationId, cause);
    }
}
