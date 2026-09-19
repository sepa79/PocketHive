package io.pockethive.mcp.application;

/**
 * Responsibility: Carry immutable validation upload outcome application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record ValidationUploadOutcome(BundleValidationReceiptView validationReceipt) implements UploadOutcome {
}
