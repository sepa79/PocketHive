package io.pockethive.mcp.application;

import java.util.Map;

/**
 * Responsibility: Own canonical presentation defaults declared by MCP tool schemas.
 * Must not: Reproduce owner-service defaults or apply fallback chains.
 * Contract: docs/mcp/README.md.
 */
enum McpToolDefaults {
    ;
    private static final Map<McpToolId, Long> LIMITS = Map.of(
        McpToolId.DEBUG_JOURNAL, 50L,
        McpToolId.DEBUG_HIVE_JOURNAL, 50L,
        McpToolId.RUNTIME_SWARM_TIMELINE, 100L);

    static Long limitFor(McpToolId toolId) {
        return LIMITS.get(toolId);
    }

    static long requireLimitFor(McpToolId toolId) {
        Long limit = limitFor(toolId);
        if (limit == null) {
            throw new IllegalArgumentException("No MCP limit default for " + toolId.externalName());
        }
        return limit;
    }
}
