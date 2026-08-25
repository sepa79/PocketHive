package io.pockethive.mcp.adapter.mcp;

import java.util.Map;

record KnownToolFailure(String code, String message) {
    Map<String, Object> structuredContent() {
        return Map.of("code", code, "message", message);
    }
}
