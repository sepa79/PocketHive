package io.pockethive.work.api;

import java.util.Locale;

/**
 * Responsibility: define the IsoSchemaRef contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ISO-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-iso-contract.
 */
public record IsoSchemaRef(
    String schemaRegistryRoot,
    String schemaId,
    String schemaVersion,
    String schemaAdapter,
    String schemaFile
) {
    public IsoSchemaRef {
        schemaRegistryRoot = requireNonBlank(schemaRegistryRoot, "schemaRegistryRoot");
        schemaId = requireNonBlank(schemaId, "schemaId");
        schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
        schemaAdapter = requireNonBlank(schemaAdapter, "schemaAdapter").toUpperCase(Locale.ROOT);
        schemaFile = requireNonBlank(schemaFile, "schemaFile");
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
