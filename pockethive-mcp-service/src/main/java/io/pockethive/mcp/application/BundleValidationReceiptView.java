package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;

/** Principal-safe client projection of a validation receipt. */
public record BundleValidationReceiptView(
    String receiptId,
    UploadWorkflowBinding workflow,
    SourceMetadata source,
    BundleFileManifest fileManifest,
    String archiveDigest,
    String bundleContentDigest,
    String scenarioId,
    Instant createdAt
) {
    public static BundleValidationReceiptView from(BundleValidationReceipt receipt) {
        return new BundleValidationReceiptView(receipt.id(), receipt.workflowBinding(), receipt.source(),
            receipt.manifest(), receipt.archiveDigest(), receipt.bundleContentDigest(), receipt.scenarioId(),
            receipt.createdAt());
    }
}
