package io.pockethive.mcp.application;

import java.util.List;
import java.util.Map;

/**
 * Responsibility: Describe one canonical MCP tool's public contract and execution metadata.
 * Must not: Execute tools, duplicate tool ownership, or infer handler selection.
 * Contract: docs/mcp/README.md.
 */
public record ToolDescriptor(
    McpToolId toolId,
    String description,
    Map<String, Object> inputSchema,
    Map<String, Object> outputSchema,
    String requiredScope,
    boolean readOnly,
    boolean destructive,
    boolean idempotent,
    List<String> skillIds
) {
    public String id() {
        return toolId.externalName();
    }

    public ToolOwner owner() {
        return toolId.owner();
    }
}
