package io.pockethive.mcp.adapter.mcp;

import java.util.Map;

/**
 * Responsibility: Carry a stable redacted MCP failure code and message.
 * Must not: Own domain state transitions or reinterpret owner-service outcomes.
 * Contract: docs/mcp/README.md.
 */

record KnownToolFailure(String code, String message) {
    Map<String, Object> structuredContent() {
        return Map.of("code", code, "message", message);
    }
}
