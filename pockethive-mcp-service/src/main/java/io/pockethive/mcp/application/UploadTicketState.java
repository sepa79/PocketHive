package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical upload ticket state values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum UploadTicketState {
    PREPARED,
    RECEIVING,
    FAILED,
    CONSUMED
}
