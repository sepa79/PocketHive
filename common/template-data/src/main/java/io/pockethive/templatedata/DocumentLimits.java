package io.pockethive.templatedata;

import java.nio.charset.StandardCharsets;

/**
 * Responsibility: enforce the shared UTF-8 document byte budget.
 * Must not: interpret document syntax or protocols.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
final class DocumentLimits {
    static final int MAX_BYTES = 1024 * 1024;

    private DocumentLimits() {}

    static void requireSize(String document) {
        if (document == null) {
            throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "A document is required.");
        }
        if (document.length() > MAX_BYTES || document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "Document exceeds the one MiB UTF-8 limit.");
        }
    }

    static void requireSize(byte[] document) {
        if (document == null) {
            throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "A document is required.");
        }
        if (document.length > MAX_BYTES) {
            throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "Document exceeds the one MiB UTF-8 limit.");
        }
    }
}
