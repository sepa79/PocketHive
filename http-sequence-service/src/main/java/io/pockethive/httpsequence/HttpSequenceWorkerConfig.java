package io.pockethive.httpsequence;

import io.pockethive.swarm.model.BeeConfigKeys;
import io.pockethive.requesttemplates.HttpSequenceStep;
import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: validate HTTP Sequence worker settings and configured step targets.
 * Must not: execute calls or duplicate the shared step binding and portable policy.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
public record HttpSequenceWorkerConfig(
    String baseUrl,
    String templateRoot,
    String serviceId,
    Integer threadCount,
    List<HttpSequenceStep> steps,
    DebugCapture debugCapture,
    Map<String, Object> vars,
    Map<String, Object> privateConfig
) implements MaxInFlightConfig {

  public HttpSequenceWorkerConfig(String baseUrl,
                                  String templateRoot,
                                  String serviceId,
                                  Integer threadCount,
                                  List<HttpSequenceStep> steps,
                                  DebugCapture debugCapture,
                                  Map<String, Object> vars) {
    this(baseUrl, templateRoot, serviceId, threadCount, steps, debugCapture, vars, Map.of());
  }

  public HttpSequenceWorkerConfig {
    baseUrl = requireNonBlank(baseUrl, "baseUrl");
    templateRoot = requireNonBlank(templateRoot, "templateRoot");
    serviceId = requireNonBlank(serviceId, "serviceId");
    threadCount = requirePositive(threadCount, "threadCount");
    steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    for (int index = 0; index < steps.size(); index++) {
      validateStepTarget(steps.get(index), index);
    }
    debugCapture = Objects.requireNonNull(debugCapture, "debugCapture");
    vars = vars == null ? Map.of() : Map.copyOf(vars);
    privateConfig = privateConfig == null ? Map.of() : Map.copyOf(privateConfig);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> authProfileSutContext() {
    Object authProfile = privateConfig.get(BeeConfigKeys.AUTH_PROFILE);
    if (!(authProfile instanceof Map<?, ?> rawAuthProfile)) {
      return Map.of();
    }
    Object sut = rawAuthProfile.get(BeeConfigKeys.SUT);
    if (sut instanceof Map<?, ?> rawSut) {
      return (Map<String, Object>) rawSut;
    }
    return Map.of();
  }

  @Override
  public int maxInFlight() {
    return threadCount;
  }









  public enum DebugCaptureMode {
    NONE,
    ERROR_ONLY,
    SAMPLE,
    ALWAYS
  }

  public record DebugCapture(
      DebugCaptureMode mode,
      double samplePct,
      int maxBodyBytes,
      int maxJourneyBytes,
      boolean includeHeaders,
      boolean includeRequest,
      int bodyPreviewBytes,
      int redisTtlSeconds
  ) {

    public DebugCapture {
      mode = mode == null ? DebugCaptureMode.ERROR_ONLY : mode;
      samplePct = clamp01(samplePct);
      maxBodyBytes = maxBodyBytes <= 0 ? 256 * 1024 : maxBodyBytes;
      maxJourneyBytes = maxJourneyBytes <= 0 ? 1024 * 1024 : maxJourneyBytes;
      bodyPreviewBytes = bodyPreviewBytes < 0 ? 0 : bodyPreviewBytes;
      redisTtlSeconds = redisTtlSeconds <= 0 ? 120 : redisTtlSeconds;
    }

    static DebugCapture defaults() {
      return new DebugCapture(DebugCaptureMode.ERROR_ONLY, 0.0, 256 * 1024, 1024 * 1024, true, false, 4096, 120);
    }
  }

  private static double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  private static String normalise(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static void validateStepTarget(HttpSequenceStep step, int index) {
    if (step == null) {
      throw new IllegalArgumentException("steps[" + index + "] is required");
    }
    if (step.sutEndpointId() != null && step.baseUrl() != null) {
      throw new IllegalArgumentException(
          "steps[" + index + "] must not declare both sutEndpointId and baseUrl");
    }
    if (step.sutEndpointId() != null && step.sutEndpointId().isBlank()) {
      throw new IllegalArgumentException("steps[" + index + "].sutEndpointId must not be blank");
    }
    if (step.baseUrl() != null && step.baseUrl().isBlank()) {
      throw new IllegalArgumentException("steps[" + index + "].baseUrl must not be blank");
    }
  }

  private static String requireNonBlank(String value, String field) {
    String trimmed = normalise(value);
    if (trimmed == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    return trimmed;
  }

  private static int requirePositive(Integer value, String field) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(field + " must be > 0");
    }
    return value;
  }
}
