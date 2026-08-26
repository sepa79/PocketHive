package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical environment health contract values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum EnvironmentHealthContract {
    PLAIN_OK,
    SPRING_UP,
    WIREMOCK_HEALTHY,
    GRAFANA_DATABASE_OK
}
