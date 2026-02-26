package io.pockethive.clearingexport;

import java.util.Locale;
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
    String schemaVersion,
    String recordSourceStep,
    Integer recordSourceStepIndex,
    String recordBuildFailurePolicy
) {

  public ClearingExportWorkerConfig(
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
    this(
        mode,
        streamingAppendEnabled,
        streamingWindowMs,
        maxRecordsPerFile,
        flushIntervalMs,
        maxBufferedRecords,
        strictTemplate,
        lineSeparator,
        fileNameTemplate,
        headerTemplate,
        recordTemplate,
        footerTemplate,
        localTargetDir,
        localTempSuffix,
        writeManifest,
        localManifestPath,
        schemaRegistryRoot,
        schemaId,
        schemaVersion,
        "latest",
        -1,
        "stop");
  }

  public ClearingExportWorkerConfig {
    mode = defaultIfBlank(mode, "template").trim();
    streamingWindowMs = Math.max(1L, streamingWindowMs);
    maxRecordsPerFile = Math.max(1, maxRecordsPerFile);
    flushIntervalMs = Math.max(1L, flushIntervalMs);
    maxBufferedRecords = Math.max(1, maxBufferedRecords);
    lineSeparator = defaultIfBlank(lineSeparator, "\n");
    localTargetDir = requireNonBlank(localTargetDir, "localTargetDir");
    localTempSuffix = defaultIfBlank(localTempSuffix, ".tmp");
    localManifestPath = defaultIfBlank(localManifestPath, "reports/clearing/manifest.jsonl");
    schemaRegistryRoot = defaultIfBlank(schemaRegistryRoot, "/app/scenario/clearing-schemas");
    recordSourceStep = normalizeChoice(recordSourceStep, "latest");
    recordSourceStepIndex = recordSourceStepIndex == null ? -1 : recordSourceStepIndex;
    recordBuildFailurePolicy = normalizeChoice(recordBuildFailurePolicy, "stop");

    RecordSourceStep sourceStep = RecordSourceStep.from(recordSourceStep);
    if (sourceStep == RecordSourceStep.INDEX && recordSourceStepIndex < 0) {
      throw new IllegalArgumentException("recordSourceStep=index requires recordSourceStepIndex >= 0");
    }
    if (sourceStep != RecordSourceStep.INDEX && recordSourceStepIndex < -1) {
      throw new IllegalArgumentException("recordSourceStepIndex must be >= -1");
    }
    RecordBuildFailurePolicy.from(recordBuildFailurePolicy);

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

  RecordSourceStep sourceStepMode() {
    return RecordSourceStep.from(recordSourceStep);
  }

  RecordBuildFailurePolicy recordBuildFailurePolicyMode() {
    return RecordBuildFailurePolicy.from(recordBuildFailurePolicy);
  }

  private static boolean isStructuredMode(String value) {
    return "structured".equalsIgnoreCase(value);
  }

  private static String normalizeChoice(String value, String defaultValue) {
    return defaultIfBlank(value, defaultValue)
        .trim()
        .toLowerCase(Locale.ROOT)
        .replace('-', '_');
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

  enum RecordSourceStep {
    LATEST("latest"),
    FIRST("first"),
    PREVIOUS("previous"),
    INDEX("index");

    private final String value;

    RecordSourceStep(String value) {
      this.value = value;
    }

    static RecordSourceStep from(String raw) {
      for (RecordSourceStep candidate : values()) {
        if (candidate.value.equals(raw)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException(
          "recordSourceStep must be one of: latest, first, previous, index");
    }
  }

  enum RecordBuildFailurePolicy {
    SILENT_DROP("silent_drop"),
    JOURNAL_AND_LOG_ERROR("journal_and_log_error"),
    LOG_ERROR("log_error"),
    STOP("stop");

    private final String value;

    RecordBuildFailurePolicy(String value) {
      this.value = value;
    }

    static RecordBuildFailurePolicy from(String raw) {
      for (RecordBuildFailurePolicy candidate : values()) {
        if (candidate.value.equals(raw)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException(
          "recordBuildFailurePolicy must be one of: silent_drop, journal_and_log_error, log_error, stop");
    }
  }
}
