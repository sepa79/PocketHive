package io.pockethive.clearingexport;

import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    String recordBuildFailurePolicy,
    boolean businessCodeFilterEnabled,
    List<String> businessCodeAllowList,
    String businessCodeSourceStep,
    Integer businessCodeSourceStepIndex
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
        "stop",
        false,
        List.of(),
        null,
        -1);
  }

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
      String schemaVersion,
      String recordSourceStep,
      Integer recordSourceStepIndex,
      String recordBuildFailurePolicy
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
        recordSourceStep,
        recordSourceStepIndex,
        recordBuildFailurePolicy,
        false,
        List.of(),
        null,
        -1);
  }

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
      String schemaVersion,
      String recordSourceStep,
      Integer recordSourceStepIndex,
      String recordBuildFailurePolicy,
      boolean businessCodeFilterEnabled,
      List<String> businessCodeAllowList
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
        recordSourceStep,
        recordSourceStepIndex,
        recordBuildFailurePolicy,
        businessCodeFilterEnabled,
        businessCodeAllowList,
        null,
        -1);
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
    businessCodeAllowList = normalizeBusinessCodes(businessCodeAllowList);
    businessCodeSourceStepIndex = businessCodeSourceStepIndex == null ? -1 : businessCodeSourceStepIndex;

    RecordSourceStep sourceStep = RecordSourceStep.from(recordSourceStep);
    if (sourceStep == RecordSourceStep.INDEX && recordSourceStepIndex < 0) {
      throw new IllegalArgumentException("recordSourceStep=index requires recordSourceStepIndex >= 0");
    }
    if (sourceStep != RecordSourceStep.INDEX && recordSourceStepIndex < -1) {
      throw new IllegalArgumentException("recordSourceStepIndex must be >= -1");
    }
    RecordBuildFailurePolicy.from(recordBuildFailurePolicy);
    if (businessCodeFilterEnabled && businessCodeAllowList.isEmpty()) {
      throw new IllegalArgumentException(
          "businessCodeAllowList must be configured when businessCodeFilterEnabled=true");
    }
    if (businessCodeFilterEnabled && (businessCodeSourceStep == null || businessCodeSourceStep.isBlank())) {
      throw new IllegalArgumentException(
          "businessCodeSourceStep must be explicitly set when businessCodeFilterEnabled=true");
    }
    businessCodeSourceStep = normalizeChoice(businessCodeSourceStep, "latest");
    RecordSourceStep businessSourceStep;
    try {
      businessSourceStep = RecordSourceStep.from(businessCodeSourceStep);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "businessCodeSourceStep must be one of: latest, first, previous, index");
    }
    if (businessSourceStep == RecordSourceStep.INDEX && businessCodeSourceStepIndex < 0) {
      throw new IllegalArgumentException(
          "businessCodeSourceStep=index requires businessCodeSourceStepIndex >= 0");
    }
    if (businessSourceStep != RecordSourceStep.INDEX && businessCodeSourceStepIndex < -1) {
      throw new IllegalArgumentException(
          "businessCodeSourceStepIndex must be >= -1");
    }

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

  RecordSourceStep businessCodeSourceStepMode() {
    return RecordSourceStep.from(businessCodeSourceStep);
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

  private static List<String> normalizeBusinessCodes(List<String> rawCodes) {
    if (rawCodes == null || rawCodes.isEmpty()) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String code : rawCodes) {
      if (code == null) {
        continue;
      }
      String cleaned = code.trim().toUpperCase(Locale.ROOT);
      if (!cleaned.isEmpty()) {
        normalized.add(cleaned);
      }
    }
    if (normalized.isEmpty()) {
      return List.of();
    }
    return List.copyOf(normalized);
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
