package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical upload workflow mode values.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: RESP-MCP-UPLOAD-LIFECYCLE - docs/architecture/runtime-responsibilities.md#resp-mcp-upload-lifecycle.
 */

public enum UploadWorkflowMode {
    DIRECT,
    WORKFLOW,
    LEGACY_WORKFLOW
}
