package io.pockethive.mcp.adapter.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.ToolExecutionException;
import java.io.IOException;

/**
 * Responsibility: Normalize application results into MCP structured-content-safe values.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes.
 * Contract: docs/mcp/README.md.
 */

final class ToolStructuredContent {
    private ToolStructuredContent() {
    }

    static Object normalize(ObjectMapper mapper, Object value) {
        try {
            return mapper.readValue(mapper.writeValueAsBytes(value), Object.class);
        } catch (IOException exception) {
            throw new ToolExecutionException(
                "TOOL_RESULT_SERIALIZATION_FAILED",
                "Tool result could not be represented as JSON");
        }
    }
}
