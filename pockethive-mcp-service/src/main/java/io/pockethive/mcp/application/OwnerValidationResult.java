package io.pockethive.mcp.application;

/**
 * Responsibility: Carry immutable owner validation result application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record OwnerValidationResult(boolean valid, String scenarioId, String scenarioName, String bundleContentDigest,
                                    Object ownerResult) {
}
