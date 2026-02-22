package io.pockethive.clearingexport;

import java.util.Objects;

public record ClearingExportWorkerConfig(
    String mode,
    boolean streamingAppendEnabled,
    long streamingWindowMs,
    int maxRecordsPerFile,
    long flushIntervalMs,
    int maxBufferedRecords,
    boolean strictTemplate,
    String lineSeparator,
    String fileNameTemplate,
    String headerTemplate,
    String recordTemplate,
    String footerTemplate,
    String localTargetDir,
    String localTempSuffix,
    boolean writeManifest,
    String localManifestPath,
    String schemaRegistryRoot,
    String schemaId,
    String schemaVersion
) {

  public ClearingExportWorkerConfig {
    mode = defaultIfBlank(mode, "template");
    streamingWindowMs = Math.max(1L, streamingWindowMs);
    maxRecordsPerFile = Math.max(1, maxRecordsPerFile);
    flushIntervalMs = Math.max(1L, flushIntervalMs);
    maxBufferedRecords = Math.max(1, maxBufferedRecords);
    lineSeparator = defaultIfBlank(lineSeparator, "\n");
    localTargetDir = requireNonBlank(localTargetDir, "localTargetDir");
    localTempSuffix = defaultIfBlank(localTempSuffix, ".tmp");
    localManifestPath = defaultIfBlank(localManifestPath, "reports/clearing/manifest.jsonl");
    schemaRegistryRoot = defaultIfBlank(schemaRegistryRoot, "/app/scenario/clearing-schemas");

    if (isStructuredMode(mode)) {
      if (streamingAppendEnabled) {
        throw new IllegalArgumentException("streamingAppendEnabled is supported only in template mode");
      }
      schemaId = requireNonBlank(schemaId, "schemaId");
      schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
    } else {
      fileNameTemplate = requireNonBlank(fileNameTemplate, "fileNameTemplate");
      headerTemplate = requireNonBlank(headerTemplate, "headerTemplate");
      recordTemplate = requireNonBlank(recordTemplate, "recordTemplate");
      footerTemplate = requireNonBlank(footerTemplate, "footerTemplate");
    }
  }

  boolean structuredMode() {
    return isStructuredMode(mode);
  }

  private static boolean isStructuredMode(String value) {
    return "structured".equalsIgnoreCase(value);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value.trim();
  }

  private static String defaultIfBlank(String value, String defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return Objects.requireNonNull(defaultValue, "defaultValue");
    }
    return value;
  }
}
