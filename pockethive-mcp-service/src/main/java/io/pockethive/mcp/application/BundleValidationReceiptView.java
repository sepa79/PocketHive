package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;

/**
 * Responsibility: Carry immutable bundle validation receipt view application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

/** Principal-safe client projection of a validation receipt. */
public record BundleValidationReceiptView(
    String receiptId,
    UploadWorkflowBinding workflow,
    SourceMetadata source,
    BundleFileManifest fileManifest,
    String archiveDigest,
    String bundleContentDigest,
    String scenarioId,
    String scenarioName,
    Instant createdAt
) {
    public static BundleValidationReceiptView from(BundleValidationReceipt receipt) {
        return new BundleValidationReceiptView(receipt.id(), receipt.workflowBinding(), receipt.source(),
            receipt.manifest(), receipt.archiveDigest(), receipt.bundleContentDigest(), receipt.scenarioId(),
            receipt.scenarioName(), receipt.createdAt());
    }
}
