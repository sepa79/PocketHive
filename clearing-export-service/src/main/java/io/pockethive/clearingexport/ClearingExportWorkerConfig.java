package io.pockethive.clearingexport;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record ClearingExportWorkerConfig(
    String mode,
    Boolean streamingAppendEnabled,
    Long streamingWindowMs,
    Integer maxRecordsPerFile,
    Long flushIntervalMs,
    Integer maxBufferedRecords,
    Boolean strictTemplate,
    String lineSeparator,
    String fileNameTemplate,
    String headerTemplate,
    String recordTemplate,
    String footerTemplate,
    String localTargetDir,
    String localTempSuffix,
    Boolean writeManifest,
    String localManifestPath,
    String schemaRegistryRoot,
    String schemaId,
    String schemaVersion,
    String recordSourceStep,
    Integer recordSourceStepIndex,
    String recordBuildFailurePolicy,
    Boolean businessCodeFilterEnabled,
    List<String> businessCodeAllowList,
    String businessCodeSourceStep,
    Integer businessCodeSourceStepIndex
) {
  private static final String MODE_TEMPLATE = "template";
  private static final String MODE_STRUCTURED = "structured";

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
    mode = normalizeRequiredChoice(mode, "mode");
    requireMode(mode);
    streamingAppendEnabled = requirePresent(streamingAppendEnabled, "streamingAppendEnabled");
    streamingWindowMs = requireNonNegative(streamingWindowMs, "streamingWindowMs");
    if (streamingAppendEnabled && streamingWindowMs <= 0L) {
      throw new IllegalArgumentException("streamingWindowMs must be > 0 when streamingAppendEnabled=true");
    }
    maxRecordsPerFile = requirePositive(maxRecordsPerFile, "maxRecordsPerFile");
    flushIntervalMs = requirePositive(flushIntervalMs, "flushIntervalMs");
    maxBufferedRecords = requirePositive(maxBufferedRecords, "maxBufferedRecords");
    strictTemplate = requirePresent(strictTemplate, "strictTemplate");
    localTargetDir = requireNonBlank(localTargetDir, "localTargetDir");
    localTempSuffix = requireNonBlank(localTempSuffix, "localTempSuffix");
    writeManifest = requirePresent(writeManifest, "writeManifest");
    localManifestPath = writeManifest ? requireNonBlank(localManifestPath, "localManifestPath") : trimToNull(localManifestPath);
    recordSourceStep = normalizeRequiredChoice(recordSourceStep, "recordSourceStep");
    recordSourceStepIndex = requirePresent(recordSourceStepIndex, "recordSourceStepIndex");
    recordBuildFailurePolicy = normalizeRequiredChoice(recordBuildFailurePolicy, "recordBuildFailurePolicy");
    businessCodeFilterEnabled = requirePresent(businessCodeFilterEnabled, "businessCodeFilterEnabled");
    businessCodeAllowList = normalizeBusinessCodes(businessCodeAllowList);

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
    if (businessCodeFilterEnabled) {
      businessCodeSourceStep = normalizeRequiredChoice(businessCodeSourceStep, "businessCodeSourceStep");
      businessCodeSourceStepIndex = requirePresent(businessCodeSourceStepIndex, "businessCodeSourceStepIndex");
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
    } else {
      businessCodeSourceStep = trimToNull(businessCodeSourceStep);
      businessCodeSourceStepIndex = businessCodeSourceStepIndex == null ? -1 : businessCodeSourceStepIndex;
    }

    if (isStructuredMode(mode)) {
      if (streamingAppendEnabled) {
        throw new IllegalArgumentException("streamingAppendEnabled is supported only in template mode");
      }
      lineSeparator = trimToNull(lineSeparator);
      schemaRegistryRoot = requireNonBlank(schemaRegistryRoot, "schemaRegistryRoot");
      schemaId = requireNonBlank(schemaId, "schemaId");
      schemaVersion = requireNonBlank(schemaVersion, "schemaVersion");
    } else {
      lineSeparator = decodeLineSeparator(requireNonEmpty(lineSeparator, "lineSeparator"));
      schemaRegistryRoot = trimToNull(schemaRegistryRoot);
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
    return MODE_STRUCTURED.equalsIgnoreCase(value);
  }

  private static void requireMode(String value) {
    if (!MODE_TEMPLATE.equals(value) && !MODE_STRUCTURED.equals(value)) {
      throw new IllegalArgumentException("mode must be one of: template, structured");
    }
  }

  private static String normalizeRequiredChoice(String value, String name) {
    return requireNonBlank(value, name)
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

  private static String requireNonEmpty(String value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value;
  }

  private static String decodeLineSeparator(String value) {
    return switch (value) {
      case "\\n" -> "\n";
      case "\\r\\n" -> "\r\n";
      case "\\r" -> "\r";
      default -> value;
    };
  }

  private static String trimToNull(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    return value.trim();
  }

  private static Boolean requirePresent(Boolean value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value;
  }

  private static Integer requirePresent(Integer value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value;
  }

  private static Integer requirePositive(Integer value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
    return value;
  }

  private static Long requirePositive(Long value, String name) {
    if (value == null || value <= 0L) {
      throw new IllegalArgumentException(name + " must be > 0");
    }
    return value;
  }

  private static Long requireNonNegative(Long value, String name) {
    if (value == null || value < 0L) {
      throw new IllegalArgumentException(name + " must be >= 0");
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
    SKIP_RECORD("skip_record"),
    WARN_ONLY("warn_only"),
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
          "recordBuildFailurePolicy must be one of: stop, skip_record, warn_only");
    }
  }
}
