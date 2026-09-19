package io.pockethive.mcp.config;

/**
 * Responsibility: Name the configured persistence mode for MCP coordination state.
 * Must not: Select storage paths or implement persistence behavior.
 * Contract: pockethive.mcp.state-mode in docs/mcp/README.md.
 */
public enum McpStateMode {
    FILE,
    MEMORY
}
