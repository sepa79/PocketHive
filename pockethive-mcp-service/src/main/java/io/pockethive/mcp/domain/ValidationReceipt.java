package io.pockethive.mcp.domain;

/**
 * Responsibility: Model the ValidationReceipt MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record ValidationReceipt(String archiveDigest, String bundleContentDigest) {
}
