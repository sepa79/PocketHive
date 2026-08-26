package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical environment health status values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum EnvironmentHealthStatus {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE
}
