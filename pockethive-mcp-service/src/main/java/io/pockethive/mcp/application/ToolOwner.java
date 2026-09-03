package io.pockethive.mcp.application;

/**
 * Responsibility: Identify the service that owns execution semantics for an MCP tool.
 * Must not: Define tool identifiers, routes, or handler behavior.
 * Contract: docs/mcp/README.md.
 */
public enum ToolOwner {
    MCP,
    SCENARIO_MANAGER,
    ORCHESTRATOR
}
