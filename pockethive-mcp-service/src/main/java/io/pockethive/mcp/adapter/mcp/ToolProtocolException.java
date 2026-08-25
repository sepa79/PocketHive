package io.pockethive.mcp.adapter.mcp;

final class ToolProtocolException extends RuntimeException {
    ToolProtocolException(String correlationId, Throwable cause) {
        super("Unexpected tool failure; correlationId=" + correlationId, cause);
    }
}
