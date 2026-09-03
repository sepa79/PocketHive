package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical upload workflow mode values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public enum UploadWorkflowMode {
    DIRECT,
    WORKFLOW
}
