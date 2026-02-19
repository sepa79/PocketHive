package io.pockethive.clearingexport;

import java.util.Objects;

public record ClearingExportWorkerConfig(
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
    String localManifestPath
) {

  public ClearingExportWorkerConfig {
    maxRecordsPerFile = Math.max(1, maxRecordsPerFile);
    flushIntervalMs = Math.max(1L, flushIntervalMs);
    maxBufferedRecords = Math.max(1, maxBufferedRecords);
    lineSeparator = defaultIfBlank(lineSeparator, "\n");
    fileNameTemplate = requireNonBlank(fileNameTemplate, "fileNameTemplate");
    headerTemplate = requireNonBlank(headerTemplate, "headerTemplate");
    recordTemplate = requireNonBlank(recordTemplate, "recordTemplate");
    footerTemplate = requireNonBlank(footerTemplate, "footerTemplate");
    localTargetDir = requireNonBlank(localTargetDir, "localTargetDir");
    localTempSuffix = defaultIfBlank(localTempSuffix, ".tmp");
    localManifestPath = defaultIfBlank(localManifestPath, "reports/clearing/manifest.jsonl");
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
