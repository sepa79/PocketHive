package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical upload ticket purpose values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum UploadTicketPurpose {
    VALIDATION,
    PUBLICATION
}
