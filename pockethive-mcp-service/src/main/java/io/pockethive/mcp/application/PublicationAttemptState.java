package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical publication attempt state values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum PublicationAttemptState {
    PREPARED,
    RECEIVING,
    VERIFIED,
    OWNER_CALL_IN_FLIGHT,
    SUCCEEDED,
    FAILED,
    AMBIGUOUS
}
