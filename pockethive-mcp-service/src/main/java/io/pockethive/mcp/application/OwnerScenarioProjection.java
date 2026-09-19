package io.pockethive.mcp.application;

/**
 * Responsibility: Carry immutable owner scenario projection application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record OwnerScenarioProjection(String scenarioId, String bundleContentDigest, Object ownerResult) {
}
