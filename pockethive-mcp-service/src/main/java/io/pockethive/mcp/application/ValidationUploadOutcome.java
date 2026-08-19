package io.pockethive.mcp.application;

public record ValidationUploadOutcome(BundleValidationReceiptView validationReceipt) implements UploadOutcome {
}
